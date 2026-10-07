# MRXS → OME-TIFF Converter for QuPath

> **Status:** in development, not yet released.

QuPath can show the channels of fluorescence `.mrxs` slides incorrectly. This extension converts them to
`.ome.tiff` files, which QuPath opens correctly. It does the conversion without leaving QuPath, so there's
nothing to install besides the extension.

## Requirements

- Windows 10/11
- QuPath 0.7.x
- About 400 MB of disk space for the converter's tools, unpacked the first time you convert.
- Free disk space: roughly **13× the size of each slide** while it converts. Most of that is temporary and
  is freed once the slide is done.

## Install

Drag the extension `.jar` file onto the QuPath window, then restart QuPath.

## Use

1. **Extensions → Convert MRXS to OME-TIFF…**
2. Add slides with **Add files…**, or a whole folder with **Add folder…**. Subfolders are included.
3. Choose where the converted files go: next to each slide (the default), or in a folder you pick.
4. Click **Start**. You can keep working in QuPath while it converts.

When the conversion finishes, a summary shows which slides converted, which were skipped and which failed.

## Good to know

- Keep each `.mrxs` file next to its data folder (the folder with the same name). The slide can't be
  converted without it.
- Slides that already have a converted `.ome.tiff` are skipped, so you can safely run the same folder again.
- Your original slides are never changed or deleted.
- Converted files also include the slide's label image, which may show patient or study IDs.
- **Advanced** settings are optional. The defaults match the standard conversion.

## Uninstall

Delete the extension `.jar` from QuPath's extensions folder (default `%USERPROFILE%\QuPath\v0.7\extensions\`), then delete `%LOCALAPPDATA%\mantis-mrxsconvert`.

---

Built on [bioformats2raw](https://github.com/glencoesoftware/bioformats2raw) and
[raw2ometiff](https://github.com/glencoesoftware/raw2ometiff). Licensed under GPL-3.0.
