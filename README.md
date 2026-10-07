# MRXS → OME-TIFF Converter for QuPath

QuPath can show the channels of fluorescence `.mrxs` slides incorrectly. This extension converts them to
`.ome.tiff` files, which QuPath opens correctly. It does the conversion without leaving QuPath, so there's
nothing to install besides the extension.

## Requirements

- Windows 10/11 (64-bit). macOS and Linux are not supported.
- QuPath 0.7.x
- About 400 MB of disk space for the converter's tools, unpacked the first time you convert.
- Free disk space: roughly **13× the size of each slide** while it converts. Most of that is temporary and
  is freed once the slide is done.

## Install

1. Download the `.jar` file from the latest release on the
   [Releases page](https://github.com/Embodied-Space/mantis-mrxsconvert/releases).
2. Drag it onto the QuPath window, then restart QuPath.

Nothing else is needed: the extension brings its own copy of the conversion tools and of Java.

## Use

1. **Extensions → Convert MRXS to OME-TIFF…**
2. Add slides with **Add files…**, or a whole folder with **Add folder…**. Subfolders are included.
3. Choose where the converted files go: next to each slide (the default), or in a folder you pick.
4. Click **Start**. You can keep working in QuPath while it converts.

When the conversion finishes, a summary shows which slides converted, which were skipped and which failed.

## What it does and doesn't do

- It converts `.mrxs` slides to `.ome.tiff`, and nothing else. It doesn't add the converted files to a QuPath
  project, copy annotations, or convert other file formats.
- Your original slides are never changed, moved or deleted.
- Slides that already have a converted `.ome.tiff` are skipped, so you can safely run the same folder again.
- Keep each `.mrxs` file next to its data folder (the folder with the same name). The slide can't be
  converted without it.
- Converted files also include the slide's label image, which may show patient or study IDs.
- **Advanced** settings are optional. The defaults match the standard conversion.

## Logs and cache

Everything the extension keeps is in `%LOCALAPPDATA%\mantis-mrxsconvert\`:

- `logs\` has one log file per conversion. **Show log** in the summary opens it.
- `tools\` has the unpacked conversion tools (about 400 MB).

While a slide converts, its temporary files are in `%TEMP%\mantis-mrxsconvert\`. They are deleted when the
slide is done, or the next time QuPath starts if it was closed unexpectedly.

## Uninstall

1. Close QuPath.
2. Delete the extension `.jar` from QuPath's extensions folder (default `%USERPROFILE%\QuPath\v0.7\extensions\`).
3. Delete the folder `%LOCALAPPDATA%\mantis-mrxsconvert`.

---

Built on [bioformats2raw](https://github.com/glencoesoftware/bioformats2raw) and
[raw2ometiff](https://github.com/glencoesoftware/raw2ometiff). Licensed under [GPL-3.0](LICENSE).
