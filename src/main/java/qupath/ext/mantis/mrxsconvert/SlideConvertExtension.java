package qupath.ext.mantis.mrxsconvert;

import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.temp.TempWorkspace;
import qupath.ext.mantis.mrxsconvert.ui.ConverterWindow;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.util.ResourceBundle;

/**
 * Entry point: adds the conversion command to QuPath's Extensions menu (B1).
 * <p>
 * Registered via {@code META-INF/services/qupath.lib.gui.extensions.QuPathExtension}.
 */
public class SlideConvertExtension implements QuPathExtension {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.mantis.mrxsconvert.strings");
	private static final Logger logger = LoggerFactory.getLogger(SlideConvertExtension.class);

	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

	private boolean isInstalled = false;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		// Removes temp workspaces left by crashed or killed QuPath instances (spec 05, C2)
		Thread.ofPlatform().daemon().name("mrxsconvert-temp-cleanup").start(TempWorkspace::cleanupOrphans);
		var window = new ConverterWindow(qupath);
		window.installQuitGuard();
		MenuItem menuItem = new MenuItem(resources.getString("menu.convert"));
		menuItem.setOnAction(e -> window.show());
		qupath.getMenu("Extensions", false).getItems().add(menuItem);
	}

	@Override
	public String getName() {
		return resources.getString("name");
	}

	@Override
	public String getDescription() {
		return resources.getString("description");
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}
