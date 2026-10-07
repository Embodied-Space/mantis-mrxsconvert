package qupath.ext.mantis.mrxsconvert.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.batch.BatchHandle;
import qupath.ext.mantis.mrxsconvert.batch.BatchQueue;
import qupath.ext.mantis.mrxsconvert.plan.Plan;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;
import qupath.ext.mantis.mrxsconvert.settings.MachineLimits;
import qupath.ext.mantis.mrxsconvert.settings.SettingsStore;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * The extension's one window (spec 08): non-modal, owned by QuPath's main window, showing either the
 * {@link SetupView} or the {@link BatchView} of the current or last batch. Only one batch runs at a time; while it
 * runs, the menu reopens its progress. Closing the window hides it and never cancels. Use on the JavaFX thread.
 */
public final class ConverterWindow {

	private static final Logger logger = LoggerFactory.getLogger(ConverterWindow.class);

	/** Readable on QuPath's light and dark themes. */
	static final String ERROR_STYLE = "-fx-text-fill: #e53935;";
	static final String WARNING_STYLE = "-fx-text-fill: #ef6c00;";

	private final QuPathGUI qupath;
	private Stage stage;
	private SetupView setup;
	private BatchView batchView;
	/** Non-null while a batch is running. */
	private BatchHandle running;
	/** QuPath asked to quit during a batch; quit again once it has stopped. */
	private boolean quitWhenStopped;

	public ConverterWindow(QuPathGUI qupath) {
		this.qupath = qupath;
	}

	/** Asks before QuPath quits during a batch, and cancels it first (spec 08 rules, D4). Call once. */
	public void installQuitGuard() {
		// QuPath's File > Quit fires the same event at its main window, so this filter catches both
		qupath.getStage().addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, this::onQuPathCloseRequest);
	}

	/** Opens the window, or brings it to the front showing what's current. */
	public void show() {
		if (stage == null)
			createStage();
		if (running == null && batchView == null)
			stage.getScene().setRoot(setup);
		stage.show();
		stage.toFront();
		stage.requestFocus();
	}

	private void createStage() {
		stage = new Stage();
		stage.initOwner(qupath.getStage());
		stage.setTitle(UiText.get("ui.title"));
		setup = new SetupView(stage, SettingsStore.qupath(), MachineLimits.current(), this::startBatch);
		stage.setScene(new Scene(setup, 960, 720));
		stage.setMinWidth(640);
		stage.setMinHeight(480);
	}

	private void startBatch(Plan plan, ConversionSettings settings) {
		if (running != null)
			return;
		var view = new BatchView(plan.jobs(), this::backToSetup, () -> stage.hide());
		BatchHandle handle = BatchQueue.forQuPath().start(plan, settings, view.listener());
		running = handle;
		batchView = view;
		view.attach(handle);
		stage.getScene().setRoot(view);
		handle.report().whenComplete((report, error) -> Platform.runLater(() -> {
			if (error != null)
				logger.error("Batch ended unexpectedly", error);
			else
				view.finish(report);
			running = null;
			if (quitWhenStopped) {
				quitWhenStopped = false;
				qupath.sendQuitRequest();
			}
		}));
	}

	/** After a batch, back to the same selections, re-planned so converted slides show as skipped. */
	private void backToSetup() {
		if (running != null)
			return;
		batchView = null;
		stage.getScene().setRoot(setup);
		setup.replan();
	}

	private void onQuPathCloseRequest(WindowEvent event) {
		if (running == null)
			return;
		event.consume();
		if (quitWhenStopped)
			return; // Already cancelling; QuPath quits when it's done
		show();
		if (!Dialogs.showYesNoDialog(UiText.get("ui.title"), UiText.get("ui.quit.confirm")))
			return;
		if (running == null) {
			// Finished while the question was open; quit once this event is done
			Platform.runLater(qupath::sendQuitRequest);
			return;
		}
		quitWhenStopped = true;
		batchView.cancel();
	}
}
