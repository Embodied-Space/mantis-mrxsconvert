package qupath.ext.mantis.mrxsconvert.runner;

/** Coarse progress, called on the converting thread. */
@FunctionalInterface
public interface ProgressListener {

	enum Phase {
		/** bioformats2raw: {@code .mrxs} → Zarr; overall progress 0–0.5. */
		EXTRACTING,
		/** raw2ometiff: Zarr → OME-TIFF; overall progress 0.5–1. */
		WRITING
	}

	ProgressListener NONE = (phase, fraction) -> {};

	/** @param fraction overall progress of this conversion, 0–1 */
	void update(Phase phase, double fraction);
}
