package qupath.ext.mantis.mrxsconvert.ui;

import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.mantis.mrxsconvert.batch.BatchQueue;
import qupath.ext.mantis.mrxsconvert.settings.Compression;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;
import qupath.ext.mantis.mrxsconvert.settings.MachineLimits;

import java.nio.file.Path;

/**
 * The collapsed Advanced section (B6, spec 06 fields): edits every {@link ConversionSettings} field except the output
 * folder, shows the heap warning (spec 07 rule 8) and has <i>Reset to defaults</i>.
 */
final class AdvancedSettingsPane extends TitledPane {

	private static final int MAX_TILE_SIZE = 16384;
	private static final int MAX_RESOLUTIONS = 64;
	private static final int MAX_WORKERS = 256;
	/** Shown when the user turns Automatic off. */
	private static final int SUGGESTED_HEAP_MB = 4096;
	private static final double SUGGESTED_QUALITY = 0.9;

	private final MachineLimits limits;
	private final Spinner<Integer> parallel;
	private final CheckBox heapAuto = new CheckBox(UiText.get("ui.advanced.auto"));
	private final Spinner<Integer> heap;
	private final ComboBox<Compression> compression = new ComboBox<>(FXCollections.observableArrayList(Compression.values()));
	private final CheckBox qualityDefault = new CheckBox(UiText.get("ui.advanced.default"));
	private final Spinner<Double> quality;
	private final Spinner<Integer> tileWidth;
	private final Spinner<Integer> tileHeight;
	private final CheckBox resolutionsAuto = new CheckBox(UiText.get("ui.advanced.auto"));
	private final Spinner<Integer> resolutions;
	private final Spinner<Integer> workers;
	private final Label heapWarning = new Label();

	/** @param onReset called after <i>Reset to defaults</i>, to reset the output folder and forget stored values */
	AdvancedSettingsPane(MachineLimits limits, ConversionSettings initial, Runnable onReset) {
		this.limits = limits;
		var d = ConversionSettings.DEFAULTS;
		parallel = intSpinner(1, limits.maxParallelFiles(), d.parallelFiles(), 1);
		int maxHeap = Math.max(ConversionSettings.MIN_HEAP_MB, limits.maxHeapMb());
		heap = intSpinner(ConversionSettings.MIN_HEAP_MB, maxHeap, Math.min(SUGGESTED_HEAP_MB, maxHeap), 256);
		quality = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.01, 1, SUGGESTED_QUALITY, 0.05));
		commitOnFocusLost(quality);
		tileWidth = intSpinner(16, MAX_TILE_SIZE, d.tileWidth(), 256);
		tileHeight = intSpinner(16, MAX_TILE_SIZE, d.tileHeight(), 256);
		resolutions = intSpinner(1, MAX_RESOLUTIONS, 6, 1);
		workers = intSpinner(1, MAX_WORKERS, d.maxWorkers(), 1);

		compression.setCellFactory(list -> new CompressionCell());
		compression.setButtonCell(new CompressionCell());
		heap.disableProperty().bind(heapAuto.selectedProperty());
		resolutions.disableProperty().bind(resolutionsAuto.selectedProperty());
		qualityDefault.disableProperty().bind(compression.valueProperty().isNotEqualTo(Compression.JPEG));
		quality.disableProperty().bind(qualityDefault.disableProperty().or(qualityDefault.selectedProperty()));

		var grid = new GridPane();
		grid.setHgap(8);
		grid.setVgap(6);
		int row = 0;
		addRow(grid, row++, "ui.advanced.parallel", "ui.advanced.parallel.help", parallel);
		addRow(grid, row++, "ui.advanced.heap", "ui.advanced.heap.help", heap, heapAuto);
		addRow(grid, row++, "ui.advanced.compression", null, compression);
		addRow(grid, row++, "ui.advanced.quality", "ui.advanced.quality.help", quality, qualityDefault);
		addRow(grid, row++, "ui.advanced.tile-width", "ui.advanced.tile.help", tileWidth);
		addRow(grid, row++, "ui.advanced.tile-height", "ui.advanced.tile.help", tileHeight);
		addRow(grid, row++, "ui.advanced.resolutions", "ui.advanced.resolutions.help", resolutions, resolutionsAuto);
		addRow(grid, row, "ui.advanced.workers", "ui.advanced.workers.help", workers);

		heapWarning.setWrapText(true);
		heapWarning.setMinHeight(Region.USE_PREF_SIZE);
		heapWarning.setStyle(ConverterWindow.WARNING_STYLE);
		heapWarning.managedProperty().bind(heapWarning.visibleProperty());
		var reset = new Button(UiText.get("ui.advanced.reset"));
		reset.setOnAction(e -> {
			setSettings(ConversionSettings.DEFAULTS);
			onReset.run();
		});
		var content = new VBox(10, grid, heapWarning, reset);
		content.setPadding(new Insets(8));

		setText(UiText.get("ui.advanced"));
		setContent(content);
		setExpanded(false);
		setAnimated(false);

		setSettings(initial);
		for (Observable o : new Observable[]{parallel.valueProperty(), heap.valueProperty(),
				heapAuto.selectedProperty()})
			o.addListener(x -> updateHeapWarning());
		updateHeapWarning();
	}

	/** Shows {@code s}; its output folder is ignored. */
	void setSettings(ConversionSettings s) {
		parallel.getValueFactory().setValue(Math.min(s.parallelFiles(), limits.maxParallelFiles()));
		heapAuto.setSelected(s.heapMb() == null);
		if (s.heapMb() != null)
			heap.getValueFactory().setValue(s.heapMb());
		compression.setValue(s.compression());
		qualityDefault.setSelected(s.compressionQuality() == null);
		if (s.compressionQuality() != null)
			quality.getValueFactory().setValue(s.compressionQuality());
		tileWidth.getValueFactory().setValue(s.tileWidth());
		tileHeight.getValueFactory().setValue(s.tileHeight());
		resolutionsAuto.setSelected(s.resolutions() == null);
		if (s.resolutions() != null)
			resolutions.getValueFactory().setValue(s.resolutions());
		workers.getValueFactory().setValue(s.maxWorkers());
	}

	/** The settings shown, with typed-but-uncommitted spinner text taken into account. */
	ConversionSettings settings(Path outputFolder) {
		for (Spinner<?> spinner : new Spinner<?>[]{parallel, heap, quality, tileWidth, tileHeight, resolutions, workers})
			commit(spinner);
		return new ConversionSettings(parallel.getValue(), heapAuto.isSelected() ? null : heap.getValue(),
				compression.getValue(),
				compression.getValue() == Compression.JPEG && !qualityDefault.isSelected() ? quality.getValue() : null,
				tileWidth.getValue(), tileHeight.getValue(),
				resolutionsAuto.isSelected() ? null : resolutions.getValue(), workers.getValue(), outputFolder);
	}

	private void updateHeapWarning() {
		var warning = BatchQueue.heapWarning(new ConversionSettings(parallel.getValue(),
				heapAuto.isSelected() ? null : heap.getValue(), Compression.LZW, null, 1, 1, null, 1, null), limits);
		heapWarning.setText(warning.orElse(""));
		heapWarning.setVisible(warning.isPresent());
		// Users only see the warning if Advanced is open; keep it next to the field that causes it
		if (warning.isPresent())
			setExpanded(true);
	}

	private static void addRow(GridPane grid, int row, String labelKey, String helpKey, Node field, Node... extra) {
		var label = new Label(UiText.get(labelKey));
		label.setLabelFor(field);
		if (helpKey != null) {
			var tooltip = new Tooltip(UiText.get(helpKey));
			tooltip.setWrapText(true);
			tooltip.setMaxWidth(400);
			label.setTooltip(tooltip);
			if (field instanceof Control control)
				control.setTooltip(tooltip);
		}
		var box = new HBox(8, field);
		box.getChildren().addAll(extra);
		box.setAlignment(Pos.CENTER_LEFT);
		grid.addRow(row, label, box);
	}

	private static Spinner<Integer> intSpinner(int min, int max, int initial, int step) {
		var spinner = new Spinner<Integer>(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, initial, step));
		commitOnFocusLost(spinner);
		return spinner;
	}

	private static void commitOnFocusLost(Spinner<?> spinner) {
		spinner.setEditable(true);
		spinner.setPrefWidth(110);
		spinner.focusedProperty().addListener((v, was, is) -> {
			if (!is)
				commit(spinner);
		});
	}

	/** Spinners only take typed text on Enter; take it now, or put back the last value if it isn't a number. */
	private static <T> void commit(Spinner<T> spinner) {
		if (!spinner.isEditable())
			return;
		SpinnerValueFactory<T> factory = spinner.getValueFactory();
		String text = spinner.getEditor().getText();
		try {
			T value = factory.getConverter().fromString(text == null ? "" : text.strip());
			if (value != null)
				factory.setValue(value);
		} catch (RuntimeException e) {
			// Not a number: fall through
		}
		spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
	}

	private static final class CompressionCell extends ListCell<Compression> {
		@Override
		protected void updateItem(Compression item, boolean empty) {
			super.updateItem(item, empty);
			setText(empty || item == null ? null : UiText.get("ui.advanced.compression." + item.name()));
		}
	}
}
