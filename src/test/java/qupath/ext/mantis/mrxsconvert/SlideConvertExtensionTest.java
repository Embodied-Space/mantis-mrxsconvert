package qupath.ext.mantis.mrxsconvert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SlideConvertExtensionTest {

	@Test
	void metadataLoadsFromResources() {
		var extension = new SlideConvertExtension();
		assertFalse(extension.getName().isBlank());
		assertFalse(extension.getDescription().isBlank());
		assertEquals("0.7.0", extension.getQuPathVersion().toString());
	}
}
