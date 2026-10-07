package qupath.ext.mantis.mrxsconvert.ui;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.batch.BatchHandle;
import qupath.ext.mantis.mrxsconvert.batch.BatchListener;
import qupath.ext.mantis.mrxsconvert.batch.BatchReport;
import qupath.ext.mantis.mrxsconvert.batch.JobState;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.tools.GuiTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * The progress view and, once the batch ends, its summary (spec 08 §3–4). Updates come from batch threads and are
 * coalesced into one refresh on the JavaFX thread. Closing the window doesn't cancel; only {@link #cancelButton} does.
 */
final class BatchView extends BorderPane {

	private static final Logger logger = LoggerFactory.getLogger(BatchView.class);

	/** One table row. */
	private static final class Row {
		final int index;
		final PlannedJob job;
		final ObjectProperty<JobState> state = new SimpleObjectProperty<>();

		Row(int index, PlannedJob job) {
			this.index = index;
			this.job = job;
		}
	}

	private final Label header = new Label();
	private final ProgressBar overall = new ProgressBar(0);
	private final Label overallText = new Label();
	private final VBox warnings = new VBox(4);
	private final TableView<Row> table = new TableView<>();
	private final Label summary = new Label();
	private final Button cancelButton = new Button(UiText.get("ui.batch.cancel"));
	private final Button again = new Button(UiText.get("ui.batch.again"));
	private final Button close = new Button(UiText.get("ui.batch.close"));
	private final AtomicBoolean refreshPending = new AtomicBoolean();
	private final int converting;

	private volatile BatchHandle handle;

	/**
	 * @param jobs    the plan's jobs, in order
	 * @param onAgain back to the setup view
	 * @param onClose hides the window
	 */
	BatchView(List<PlannedJob> jobs, Runnable onAgain, Runnable onClose) {
		converting = (int) jobs.stream().filter(j -> j.status() == PlannedJob.Status.READY).count();
		table.getItems().setAll(IntStream.range(0, jobs.size()).mapToObj(i -> new Row(i, jobs.get(i))).toList());
		table.getColumns().setAll(List.of(slideColumn(), statusColumn(), progressColumn(), detailsColumn(),
				logColumn()));
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

		header.setStyle("-fx-font-weight: bold;");
		header.setText(UiText.get("ui.batch.running", converting));
		header.setWrapText(true);
		header.setMinHeight(Region.USE_PREF_SIZE);
		overall.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(overall, Priority.ALWAYS);
		var overallRow = new HBox(10, overall, overallText);
		overallRow.setAlignment(Pos.CENTER_LEFT);
		warnings.managedProperty().bind(warnings.visibleProperty());
		var top = new VBox(8, header, overallRow, warnings);
		BorderPane.setMargin(top, new Insets(0, 0, 10, 0));

		summary.setWrapText(true);
		summary.setMinHeight(Region.USE_PREF_SIZE);
		summary.setStyle("-fx-font-weight: bold;");
		cancelButton.setOnAction(e -> confirmCancel());
		again.setOnAction(e -> onAgain.run());
		close.setOnAction(e -> onClose.run());
		again.setVisible(false);
		again.managedProperty().bind(again.visibleProperty());
		var spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		var bottom = new HBox(10, summary, spacer, cancelButton, again, close);
		bottom.setAlignment(Pos.CENTER_LEFT);
		BorderPane.setMargin(bottom, new Insets(10, 0, 0, 0));

		setTop(top);
		setCenter(table);
		setBottom(bottom);
		setPadding(new Insets(12));
	}

	/** Pass to {@code BatchQueue.start}; may be called before {@link #attach}. */
	BatchListener listener() {
		return (index, state) -> scheduleRefresh();
	}

	/** Shows {@code batch}'s states from now on. */
	void attach(BatchHandle batch) {
		handle = batch;
		warnings.getChildren().setAll(batch.warnings().stream().map(w -> {
			var label = new Label(w);
			label.setWrapText(true);
			label.setMinHeight(Region.USE_PREF_SIZE);
			label.setStyle(ConverterWindow.WARNING_STYLE);
			return label;
		}).toList());
		warnings.setVisible(!batch.warnings().isEmpty());
		refresh();
	}

	/** True once Cancel was confirmed. */
	boolean isCancelling() {
		BatchHandle batch = handle;
		return batch != null && batch.isCancelled();
	}

	/** Cancels without asking, e.g. when QuPath quits (D4). */
	void cancel() {
		BatchHandle batch = handle;
		if (batch == null || batch.isCancelled())
			return;
		batch.cancel();
		cancelButton.setDisable(true);
		header.setText(UiText.get("ui.batch.cancelling"));
		refresh();
	}

	/** Switches to the summary. */
	void finish(BatchReport report) {
		refresh();
		header.setText(UiText.get(isCancelling() ? "ui.batch.cancelled" : "ui.batch.finished"));
		summary.setText(UiText.summary(report));
		cancelButton.setVisible(false);
		cancelButton.setManaged(false);
		again.setVisible(true);
	}

	private void confirmCancel() {
		if (Dialogs.showConfirmDialog(UiText.get("ui.title"), UiText.get("ui.batch.cancel.confirm")))
			cancel();
	}

	private void scheduleRefresh() {
		if (refreshPending.compareAndSet(false, true))
			Platform.runLater(() -> {
				refreshPending.set(false);
				refresh();
			});
	}

	private void refresh() {
		BatchHandle batch = handle;
		if (batch == null)
			return;
		long finished = 0;
		boolean newFailure = false;
		for (Row row : table.getItems()) {
			JobState state = batch.state(row.index);
			newFailure |= state instanceof JobState.Failed && !(row.state.get() instanceof JobState.Failed);
			row.state.set(state);
			if (state.isFinished())
				finished++;
		}
		// Rows keep their height, so make room for a newly shown (wrapped) failure reason
		if (newFailure)
			table.refresh();
		overall.setProgress(batch.progress());
		overallText.setText(UiText.get("ui.batch.overall", finished, table.getItems().size()));
	}

	private TableColumn<Row, String> slideColumn() {
		var column = new TableColumn<Row, String>(UiText.get("ui.batch.column.slide"));
		column.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().job.input().getFileName().toString()));
		column.setCellFactory(c -> new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty ? null : item);
				Row row = empty ? null : getTableRow().getItem();
				setTooltip(row == null ? null : new Tooltip(row.job.input() + (row.job.output() == null ? ""
						: "\n→ " + row.job.output())));
			}
		});
		column.setPrefWidth(240);
		return column;
	}

	private TableColumn<Row, JobState> statusColumn() {
		var column = new TableColumn<Row, JobState>(UiText.get("ui.batch.column.status"));
		column.setCellValueFactory(c -> c.getValue().state);
		column.setCellFactory(c -> new TableCell<>() {
			@Override
			protected void updateItem(JobState item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : UiText.state(item));
				setStyle(item instanceof JobState.Failed ? ConverterWindow.ERROR_STYLE : null);
			}
		});
		column.setPrefWidth(220);
		return column;
	}

	/** The runner only reports each step's start, so a running job shows a busy bar rather than a fake percentage. */
	private TableColumn<Row, JobState> progressColumn() {
		var column = new TableColumn<Row, JobState>(UiText.get("ui.batch.column.progress"));
		column.setCellValueFactory(c -> c.getValue().state);
		column.setCellFactory(c -> new TableCell<>() {
			private final ProgressBar bar = new ProgressBar();

			{
				bar.setMaxWidth(Double.MAX_VALUE);
			}

			@Override
			protected void updateItem(JobState item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null || item.kind() == JobState.Kind.QUEUED) {
					setGraphic(null);
					return;
				}
				bar.setProgress(item.kind() == JobState.Kind.RUNNING ? ProgressBar.INDETERMINATE_PROGRESS
						: item.kind() == JobState.Kind.SUCCEEDED ? 1 : 0);
				setGraphic(item.kind() == JobState.Kind.RUNNING || item.kind() == JobState.Kind.SUCCEEDED ? bar
						: null);
			}
		});
		column.setPrefWidth(130);
		return column;
	}

	/** The failure reason, wrapped, since disk-space messages are long. */
	private TableColumn<Row, JobState> detailsColumn() {
		var column = new TableColumn<Row, JobState>(UiText.get("ui.batch.column.details"));
		column.setCellValueFactory(c -> c.getValue().state);
		column.setCellFactory(c -> new TableCell<>() {
			private final Text text = new Text();

			{
				// The column, not the cell: a new cell is 0 wide when its height is first measured
				text.wrappingWidthProperty().bind(column.widthProperty().subtract(12));
				text.fillProperty().bind(textFillProperty());
			}

			@Override
			protected void updateItem(JobState item, boolean empty) {
				super.updateItem(item, empty);
				String reason = item instanceof JobState.Failed f ? f.reason() : null;
				text.setText(reason);
				setGraphic(empty || reason == null ? null : text);
				setTooltip(empty || reason == null ? null : new Tooltip(reason));
			}
		});
		column.setPrefWidth(320);
		return column;
	}

	/** <i>Show log</i> for every job that has one; failed jobs always do (spec 07). */
	private TableColumn<Row, JobState> logColumn() {
		var column = new TableColumn<Row, JobState>();
		column.setCellValueFactory(c -> c.getValue().state);
		column.setCellFactory(c -> new TableCell<>() {
			private final Button button = new Button(UiText.get("ui.batch.show-log"));

			@Override
			protected void updateItem(JobState item, boolean empty) {
				super.updateItem(item, empty);
				Path log = empty ? null : logFile(item);
				button.setOnAction(e -> openLog(log));
				setGraphic(log == null ? null : button);
			}
		});
		column.setPrefWidth(100);
		column.setSortable(false);
		return column;
	}

	private static Path logFile(JobState state) {
		return switch (state) {
			case JobState.Failed f -> f.logFile();
			case JobState.Succeeded s -> s.logFile();
			case null, default -> null;
		};
	}

	private static void openLog(Path log) {
		if (!Files.exists(log)) {
			Dialogs.showErrorMessage(UiText.get("ui.title"), UiText.get("ui.batch.log-missing", log));
			return;
		}
		if (!GuiTools.openFile(log.toFile()))
			logger.warn("Could not open {}", log);
	}
}
