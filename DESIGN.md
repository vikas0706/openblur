# OpenBlur design direction

## Chosen by the owner (2026-11-10)

- **Product:** OpenBlur, an Android app that finds faces in a video on the phone, lets you pick who to blur, and saves a copy at the source resolution, frame rate and codec.
- **Audience:** people who film other people and need to hide some of them before sharing: parents, teachers, reporters, street and event videographers.
- **Visual language:** "Editing room". Near-black surfaces so the footage is the brightest thing on screen, like a video editor.
- **Accent:** one warm amber, reserved for blur. If something is amber, it is blurred or it controls blur.
- **Mood:** calm, tool-like, little motion.
- **Icon:** a simple head silhouette whose face area is made of mosaic squares.
- **Antislop mode:** during the build.

Dial: ENERGY 1 / RHYTHM 2 / MOTION 1

Design Read: a single-purpose video tool for non-technical phone users, in a dark editing-room language, dial ENERGY 1 / RHYTHM 2 / MOTION 1.

## Derived by the agent from the direction above

The owner picked the direction; the values below are the agent's translation of it into tokens. Change any of them freely.

### Theme

Dark only, with no light theme and no toggle. Reason: people judge whether a face is hidden against the footage, and a dark surround keeps the footage as the brightest area. This is the same reason video editors are dark.

### Color

| Token | Value | Use | Reason |
|---|---|---|---|
| `surface` | `#0E0F11` | Screen background | Near-black, slightly cool so amber reads warm against it |
| `surfaceRaised` | `#17191C` | Panels, sheets, cards | One step up for grouping without shadows |
| `surfaceHigh` | `#22252A` | Pressed and selected rows, slider tracks | Second step for state |
| `outline` | `#33373D` | Decorative dividers only | Quiet separation; too faint (1.6:1) to mark a control on its own |
| `outlineStrong` | `#7D838C` | Borders that identify a control or its state: fields, unselected thumbnails, face boxes that are not blurred | At least 4:1 on all three surfaces |
| `text` | `#F2F3F5` | Primary text | 17:1 on `surface` |
| `textMuted` | `#A9AEB6` | Secondary text | 8:1 on `surface`, still AA on `surfaceHigh` |
| `amber` | `#FFB020` | Blurred state, blur controls, the one primary action per screen | The only accent |
| `onAmber` | `#1A1300` | Text and icons on amber | 10:1 on `amber` |
| `danger` | `#FF6B5E` | Errors, destructive confirmation | Needed for error states; never decorative |

No gradients, no glow, no glass. Elevation is expressed by the three surface steps, not by shadows.

### Type

The phone's system typeface (Roboto on most devices). Reason: a utility should feel native to the phone and add no font payload. Timecodes, sizes and bitrates use tabular figures so numbers do not shift while a video plays or an export runs.

Scale: 28 / 20 / 16 / 14 / 12 sp. Screen titles 20 medium, body 16 regular, supporting text 14, captions and timecodes 12.

### Shape

Radius 12 dp for sheets and panels, 8 dp for buttons, fields and thumbnails, 4 dp for mosaic tiles. Face thumbnails are rounded squares, not circles, because the mask shapes in the app are rectangles and ovals and a square thumbnail shows the whole face crop. Nothing is pill-shaped except the playback scrubber thumb.

### Motif

Mosaic tiles. They appear in the icon, in the analysis and export progress indicator (a row of square tiles that fill left to right), and in empty states. They do not appear as page backgrounds.

### Motion

Hover and press states, the progress tiles filling, and sheet open and close. No entrance animations, no parallax, no looping decoration. Respect the system "remove animations" setting.

### Layout

One focal point per screen: the pick-a-video action on Home, the progress tiles while analysing, the video preview in the editor, the progress tiles while exporting, the finished video on the result screen. The editor keeps the preview at the top and controls in a bottom panel within thumb reach. Minimum touch target 48 dp.

### Voice

Plain, short, second person. Say what will happen to the user's video. No exclamation marks, no buzzwords, no claims the app cannot back with something the user can check on their own phone.
