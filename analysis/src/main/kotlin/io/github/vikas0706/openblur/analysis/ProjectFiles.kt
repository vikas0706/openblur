package io.github.vikas0706.openblur.analysis

import io.github.vikas0706.openblur.api.ProjectSummary
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.AnalysisResultCodec
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.EditStateCodec
import io.github.vikas0706.openblur.core.SourceVideoInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class StoredProject(val meta: ProjectMeta, val result: AnalysisResult, val edit: EditState)

/**
 * The project store without Android types. A project is a folder under [root]: meta.bin, result-N.bin,
 * edit-N.bin and thumbs/. N is the generation in meta.bin, so renaming a new meta.bin in swaps a scan in one step.
 */
internal class ProjectFiles(
    private val root: File,
    private val resultCodec: AnalysisResultCodec,
    private val editCodec: EditStateCodec,
    private val encoder: ThumbnailEncoder,
    private val io: CoroutineContext,
    private val nowMs: () -> Long,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val thumbnails = ConcurrentHashMap<String, DiskThumbnails>()

    // Ids handed out or opened by this process. Their folders may belong to a scan that is still running.
    private val inUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun newId(): String = UUID.randomUUID().toString().also { inUse.add(it) }

    fun thumbnails(id: String): DiskThumbnails {
        requireValidId(id)
        inUse.add(id)
        return thumbnails.computeIfAbsent(id) { DiskThumbnails(thumbsFolder(id), encoder) }
    }

    suspend fun save(
        id: String,
        sourceUri: String,
        displayName: String,
        info: SourceVideoInfo,
        options: AnalysisOptions,
        result: AnalysisResult,
        edit: EditState,
    ) {
        requireValidId(id)
        writing(id) {
            val folder = folder(id)
            val previous = readMetaOrNull(folder)
            val generation = (previous?.generation ?: 0) + 1
            val now = nowMs()
            AtomicFiles.write(resultFile(folder, generation)) { resultCodec.write(result, it) }
            writeEdit(editFile(folder, generation), edit, now)
            val referenced = referencedThumbnails(result)
            val pictures = thumbnails(id)
            pictures.sync(referenced)
            val meta = ProjectMeta(
                generation = generation,
                sourceUri = sourceUri,
                displayName = displayName,
                info = info,
                options = options,
                createdAtMs = previous?.createdAtMs ?: now,
                updatedAtMs = now,
                durationUs = result.durationUs,
                peopleCount = result.people.size,
                coverHandle = result.people.firstOrNull()?.thumbnailHandle ?: -1,
            )
            AtomicFiles.write(metaFile(folder)) { ProjectMetaCodec.write(meta, it) }
            removeOtherGenerations(folder, generation)
            pictures.removeUnreferenced(referenced)
        }
    }

    /** Does nothing when the project was never saved or has been deleted, so a late edit cannot bring it back. */
    suspend fun saveEdit(id: String, edit: EditState) {
        requireValidId(id)
        writing(id) {
            val folder = folder(id)
            val meta = readMetaOrNull(folder) ?: return@writing
            writeEdit(editFile(folder, meta.generation), edit, nowMs())
        }
    }

    /**
     * Null when the project is unsaved, [sourceReadable] rejects its source, or it is damaged and so deleted.
     * A damaged edit alone becomes [EditState]'s defaults, which blur every face, and the scan is kept.
     */
    suspend fun load(id: String, sourceReadable: (String) -> Boolean): StoredProject? {
        if (!isValidId(id)) return null
        return lockFor(id).withLock {
            withContext(io) {
                val folder = folder(id)
                if (!metaFile(folder).isFile) return@withContext null
                val meta = try {
                    read(metaFile(folder), ProjectMetaCodec::read)
                } catch (e: IOException) {
                    remove(id)
                    return@withContext null
                }
                if (!sourceReadable(meta.sourceUri)) return@withContext null
                val result = try {
                    read(resultFile(folder, meta.generation), resultCodec::read)
                } catch (e: IOException) {
                    remove(id)
                    return@withContext null
                }
                val edit = try {
                    read(editFile(folder, meta.generation), editCodec::read)
                } catch (e: IOException) {
                    editFile(folder, meta.generation).delete()
                    EditState()
                }
                StoredProject(meta, result, edit)
            }
        }
    }

    /** Newest first. Projects whose metadata is damaged are deleted on the way. */
    suspend fun list(): List<ProjectSummary> = withContext(io) {
        val summaries = ArrayList<ProjectSummary>()
        for (folder in projectFolders()) {
            val id = folder.name
            if (!metaFile(folder).isFile) continue
            val meta = try {
                read(metaFile(folder), ProjectMetaCodec::read)
            } catch (e: IOException) {
                lockFor(id).withLock {
                    // Checked again under the lock: a save may have replaced the file since it was read.
                    if (readMetaOrNull(folder) == null) remove(id)
                }
                continue
            }
            val cover = File(thumbsFolder(id), ThumbnailNames.fileName(meta.coverHandle))
            summaries.add(
                ProjectSummary(
                    id = id,
                    displayName = meta.displayName,
                    durationUs = meta.durationUs,
                    peopleCount = meta.peopleCount,
                    cover = cover.takeIf { meta.coverHandle >= 0 && it.isFile },
                    // The edit file changes without the metadata being rewritten.
                    updatedAtMs = maxOf(meta.updatedAtMs, editFile(folder, meta.generation).lastModified()),
                ),
            )
        }
        summaries.sortWith(compareByDescending<ProjectSummary> { it.updatedAtMs }.thenBy { it.id })
        summaries
    }

    /** Returns the deleted project's source Uri when no other saved project refers to it, otherwise null. */
    suspend fun delete(id: String): String? {
        if (!isValidId(id)) return null
        var orphaned: String? = null
        writing(id) {
            val source = readMetaOrNull(folder(id))?.sourceUri
            remove(id)
            if (source != null && !isSourceOfAnyProject(source)) orphaned = source
        }
        return orphaned
    }

    /** Removes [id]'s folder unless the project was saved, so an ended scan's pictures go at once. */
    suspend fun discardUnsaved(id: String) {
        if (!isValidId(id)) return
        writing(id) {
            if (!metaFile(folder(id)).exists()) remove(id)
        }
    }

    // Reads other projects without their locks: meta.bin is only ever replaced whole.
    private fun isSourceOfAnyProject(sourceUri: String): Boolean =
        projectFolders().any { readMetaOrNull(it)?.sourceUri == sourceUri }

    /** Removes folders without metadata, last written over [ABANDONED_AFTER_MS] ago, that are not in use here. */
    suspend fun deleteAbandoned() = withContext(io) {
        val now = nowMs()
        for (folder in projectFolders()) {
            val id = folder.name
            if (id in inUse || metaFile(folder).exists()) continue
            if (now - newestChange(folder) <= ABANDONED_AFTER_MS) continue
            lockFor(id).withLock {
                if (id !in inUse && !metaFile(folder).exists()) remove(id)
            }
        }
    }

    /**
     * Takes the project's lock before dispatching to [io], so calls made in order from one thread are written
     * in that order. Not cancellable: a save that was asked for completes even if the caller's scope ends.
     */
    private suspend fun writing(id: String, block: () -> Unit) {
        withContext(NonCancellable) {
            lockFor(id).withLock {
                withContext(io) { block() }
            }
        }
    }

    private fun lockFor(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    // The file's own time is what list() reports as the last change, so it follows the store's clock.
    private fun writeEdit(file: File, edit: EditState, now: Long) {
        AtomicFiles.write(file) { editCodec.write(edit, it) }
        file.setLastModified(now)
    }

    // Callers hold the project's lock.
    private fun remove(id: String) {
        thumbnails.remove(id)?.retire()
        inUse.remove(id)
        AtomicFiles.deleteTree(folder(id))
    }

    private fun readMetaOrNull(folder: File): ProjectMeta? = try {
        read(metaFile(folder), ProjectMetaCodec::read)
    } catch (e: IOException) {
        null
    }

    private fun removeOtherGenerations(folder: File, generation: Int) {
        val keep = setOf(META_FILE, resultFile(folder, generation).name, editFile(folder, generation).name)
        folder.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keep) file.delete()
        }
    }

    private fun referencedThumbnails(result: AnalysisResult): Set<Int> {
        val handles = HashSet<Int>()
        for (track in result.tracks) if (track.thumbnailHandle >= 0) handles.add(track.thumbnailHandle)
        for (person in result.people) if (person.thumbnailHandle >= 0) handles.add(person.thumbnailHandle)
        return handles
    }

    private fun projectFolders(): List<File> =
        root.listFiles().orEmpty().filter { it.isDirectory && isValidId(it.name) }

    // Thumbnails are written into thumbs/, which changes that folder's time but not the project folder's.
    private fun newestChange(folder: File): Long {
        var newest = folder.lastModified()
        val thumbs = File(folder, THUMBS_FOLDER)
        newest = maxOf(newest, thumbs.lastModified())
        thumbs.listFiles()?.forEach { newest = maxOf(newest, it.lastModified()) }
        return newest
    }

    private fun folder(id: String) = File(root, id)

    private fun thumbsFolder(id: String) = File(folder(id), THUMBS_FOLDER)

    private fun metaFile(folder: File) = File(folder, META_FILE)

    private fun resultFile(folder: File, generation: Int) = File(folder, "result-$generation.bin")

    private fun editFile(folder: File, generation: Int) = File(folder, "edit-$generation.bin")

    private fun requireValidId(id: String) {
        require(isValidId(id)) { "Not a project id: $id" }
    }

    companion object {
        const val ABANDONED_AFTER_MS = 60L * 60L * 1000L
        const val META_FILE = "meta.bin"
        const val THUMBS_FOLDER = "thumbs"

        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /** Ids become folder names, so anything that could name another path is refused. */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        // A codec fed damaged bytes may fail with something other than IOException; both mean the file is unusable.
        private fun <T> read(file: File, decode: (InputStream) -> T): T = try {
            BufferedInputStream(FileInputStream(file), 64 * 1024).use(decode)
        } catch (e: RuntimeException) {
            throw IOException("Unreadable file ${file.name}", e)
        }
    }
}
