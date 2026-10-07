package qupath.ext.mantis.mrxsconvert.settings;

/**
 * Output compression choices (E6). JPEG-2000 is left out: raw2ometiff 0.10.1 silently writes it uncompressed
 * (spike 01).
 */
public enum Compression {
	LZW("LZW"),
	JPEG("JPEG"),
	UNCOMPRESSED("UNCOMPRESSED");

	private final String cliValue;

	Compression(String cliValue) {
		this.cliValue = cliValue;
	}

	/** The value of raw2ometiff's {@code --compression}. */
	public String cliValue() {
		return cliValue;
	}
}
