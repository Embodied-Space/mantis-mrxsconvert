package qupath.ext.mantis.mrxsconvert.runner;

import java.nio.file.Path;

/**
 * Called between the two steps, once the Zarr exists. The batch queue uses it to re-check disk space for
 * uncompressed output, whose size is only known from the Zarr (spec 07).
 */
@FunctionalInterface
public interface StepGate {

	StepGate NONE = zarr -> null;

	/**
	 * @param zarr the Zarr written by step 1
	 * @return null to go on to step 2, or a user-facing reason to fail the conversion
	 */
	String beforeWriting(Path zarr);
}
