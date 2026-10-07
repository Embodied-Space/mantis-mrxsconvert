package qupath.ext.mantis.mrxsconvert.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mantis.mrxsconvert.plan.InputPlanner;
import qupath.ext.mantis.mrxsconvert.plan.Plan;
import qupath.ext.mantis.mrxsconvert.plan.PlannedJob;
import qupath.ext.mantis.mrxsconvert.plan.Selection;
import qupath.ext.mantis.mrxsconvert.settings.ConversionSettings;
import qupath.ext.mantis.mrxsconvert.settings.MachineLimits;
import qupath.ext.mantis.mrxsconvert.settings.SettingsStore;
import qupath.fx.dialogs.FileChoosers;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The first page of the dialog (spec 08 §2): what to convert, where to, Advanced settings, Start. Every change is
 * re-planned on a background thread; Start is enabled only for the latest plan, when it has no problems and at least
 * one READY job. Use on the JavaFX thread.
 */
final class SetupView extends BorderPane {

	private static final Logger logger = LoggerFactory.getLogger(SetupView.class);

	private final Window owner;
	private final SettingsStore store;
	private final BiConsumer<Plan, ConversionSettings> onStart;

	private final ObservableList<Selection> selections = FXCollections.observableArrayList();
	private final ListView<Selection> selectionList = new ListView<>(selections);
	private final TableView<PlannedJob> jobTable = new TableView<>();
	private final RadioButton nextToSlide = new RadioButton(UiText.get("ui.setup.output.next-to"));
	private final RadioButton toFolder = new RadioButton(UiText.get("ui.setup.output.folder"));
	private final TextField outputField = new TextField();
	private final VBox problemBox = new VBox(4);
	private final Label hint = new Label();
	private final Button start = new Button(UiText.get("ui.setup.start"));
	private final AdvancedSettingsPane advanced;

	private Path outputFolder;
	private File lastFolder;

	/** Planning reads the file system, so it runs here; only the newest request's result is used. */
	private final ExecutorService planner = Executors.newSingleThreadExecutor(
			r -> Thread.ofPlatform().daemon().name("mrxsconvert-plan").unstarted(r));
	private final AtomicLong planGeneration = new AtomicLong();
	private Plan plan;
	private boolean planning;

	/** @param onStart called with the plan and settings when the user presses Start; settings are already saved */
	SetupView(Window owner, SettingsStore store, MachineLimits limits, BiConsumer<Plan, ConversionSettings> onStart) {
		this.owner = owner;
		this.store = store;
		this.onStart = onStart;
		ConversionSettings settings = store.load();
		advanced = new AdvancedSettingsPane(limits, settings, () -> {
			store.reset();
			setOutputFolder(null);
		});

		var intro = new Label(UiText.get("ui.setup.intro"));
		intro.setWrapText(true);
		intro.setMinHeight(Region.USE_PREF_SIZE);

		var top = new VBox(10, intro, selectionPane(), jobPane());
		VBox.setVgrow(top.getChildren().get(2), Priority.ALWAYS);

		var problemsAndAdvanced = new VBox(10, outputPane(), problemBox, advanced);
		problemBox.managedProperty().bind(problemBox.visibleProperty());

		hint.setWrapText(true);
		hint.setMinHeight(Region.USE_PREF_SIZE);
		start.setDefaultButton(true);
		start.setOnAction(e -> start());
		var spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox.setHgrow(hint, Priority.SOMETIMES);
		var bottom = new HBox(10, hint, spacer, start);
		bottom.setAlignment(Pos.CENTER_LEFT);

		var center = new VBox(10, top, problemsAndAdvanced);
		VBox.setVgrow(top, Priority.ALWAYS);
		setCenter(center);
		setBottom(bottom);
		BorderPane.setMargin(bottom, new Insets(10, 0, 0, 0));
		setPadding(new Insets(12));

		setOutputFolder(settings.lastOutputFolder());
		selections.addListener((ListChangeListener<Selection>) c -> replan());
		replan();
	}

	private VBox selectionPane() {
		selectionList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		selectionList.setPrefHeight(110);
		selectionList.setPlaceholder(new Label(UiText.get("ui.setup.no-slides")));
		selectionList.setCellFactory(list -> new ListCell<>() {
			@Override
			protected void updateItem(Selection item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null)
					setText(null);
				else if (item instanceof Selection.Folder)
					setText(item.path() + "  " + UiText.get("ui.setup.folder-suffix"));
				else
					setText(item.path().toString());
			}
		});

		var addFiles = new Button(UiText.get("ui.setup.add-files"));
		addFiles.setOnAction(e -> addFiles());
		var addFolder = new Button(UiText.get("ui.setup.add-folder"));
		addFolder.setOnAction(e -> addFolder());
		var remove = new Button(UiText.get("ui.setup.remove"));
		remove.disableProperty().bind(selectionList.getSelectionModel().selectedItemProperty().isNull());
		remove.setOnAction(e -> selections.removeAll(List.copyOf(selectionList.getSelectionModel().getSelectedItems())));
		var clear = new Button(UiText.get("ui.setup.clear"));
		clear.disableProperty().bind(Bindings.isEmpty(selections));
		clear.setOnAction(e -> selections.clear());

		var buttons = new HBox(8, addFiles, addFolder, remove, clear);
		return new VBox(4, new Label(UiText.get("ui.setup.added")), buttons, selectionList);
	}

	private VBox jobPane() {
		var slide = new TableColumn<PlannedJob, String>(UiText.get("ui.setup.column.slide"));
		slide.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().input().getFileName().toString()));
		slide.setCellFactory(c -> pathTooltipCell(PlannedJob::input));
		slide.setPrefWidth(260);
		var output = new TableColumn<PlannedJob, String>(UiText.get("ui.setup.column.output"));
		output.setCellValueFactory(c -> new ReadOnlyStringWrapper(
				c.getValue().output() == null ? "" : c.getValue().output().toString()));
		output.setCellFactory(c -> pathTooltipCell(PlannedJob::output));
		output.setPrefWidth(380);
		var status = new TableColumn<PlannedJob, PlannedJob>(UiText.get("ui.setup.column.status"));
		status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
		status.setCellFactory(c -> new TableCell<>() {
			@Override
			protected void updateItem(PlannedJob item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setTooltip(null);
					setStyle(null);
					return;
				}
				String text = UiText.plannedStatus(item);
				setText(text);
				setTooltip(new Tooltip(text));
				setStyle(item.status() == PlannedJob.Status.INVALID ? ConverterWindow.ERROR_STYLE : null);
			}
		});
		status.setPrefWidth(260);
		jobTable.getColumns().setAll(List.of(slide, output, status));
		jobTable.setPlaceholder(new Label(UiText.get("ui.setup.no-slides")));
		jobTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		VBox.setVgrow(jobTable, Priority.ALWAYS);
		return new VBox(4, new Label(UiText.get("ui.setup.slides")), jobTable);
	}

	private static TableCell<PlannedJob, String> pathTooltipCell(Function<PlannedJob, Path> path) {
		return new TableCell<>() {
			@Override
			protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty ? null : item);
				PlannedJob job = empty ? null : getTableRow().getItem();
				Path p = job == null ? null : path.apply(job);
				setTooltip(p == null ? null : new Tooltip(p.toString()));
			}
		};
	}

	private VBox outputPane() {
		var group = new ToggleGroup();
		nextToSlide.setToggleGroup(group);
		toFolder.setToggleGroup(group);
		outputField.setEditable(false);
		outputField.setFocusTraversable(false);
		HBox.setHgrow(outputField, Priority.ALWAYS);
		var browse = new Button(UiText.get("ui.setup.output.browse"));
		browse.setOnAction(e -> chooseOutputFolder());
		nextToSlide.setOnAction(e -> replan());
		toFolder.setOnAction(e -> {
			if (outputFolder == null)
				chooseOutputFolder();
			replan();
		});
		var folderRow = new HBox(8, toFolder, outputField, browse);
		folderRow.setAlignment(Pos.CENTER_LEFT);
		return new VBox(6, new Label(UiText.get("ui.setup.output")), nextToSlide, folderRow);
	}

	private void addFiles() {
		List<File> files = FileChoosers.promptForMultipleFiles(owner, UiText.get("ui.setup.choose-files"),
				new FileChooser.ExtensionFilter(UiText.get("ui.setup.mrxs-filter"), "*.mrxs", "*.MRXS"));
		if (files == null || files.isEmpty())
			return;
		lastFolder = files.getFirst().getParentFile();
		add(files.stream().<Selection>map(f -> new Selection.File(f.toPath())).toList());
	}

	private void addFolder() {
		File folder = FileChoosers.promptForDirectory(owner, UiText.get("ui.setup.choose-folder"), lastFolder);
		if (folder == null)
			return;
		lastFolder = folder;
		add(List.of(new Selection.Folder(folder.toPath())));
	}

	private void add(List<Selection> added) {
		List<Selection> fresh = new ArrayList<>();
		for (Selection s : added)
			if (!selections.contains(s) && !fresh.contains(s))
				fresh.add(s);
		selections.addAll(fresh);
	}

	private void chooseOutputFolder() {
		File initial = outputFolder != null ? outputFolder.toFile() : lastFolder;
		File folder = FileChoosers.promptForDirectory(owner, UiText.get("ui.setup.output.choose"),
				initial != null && initial.isDirectory() ? initial : null);
		if (folder != null)
			setOutputFolder(folder.toPath());
		else if (outputFolder == null)
			nextToSlide.setSelected(true);
		replan();
	}

	/** @param folder null to save next to each slide */
	private void setOutputFolder(Path folder) {
		outputFolder = folder == null ? null : folder.toAbsolutePath().normalize();
		outputField.setText(outputFolder == null ? "" : outputFolder.toString());
		(outputFolder == null ? nextToSlide : toFolder).setSelected(true);
		replan();
	}

	/** The output folder override, if "In this folder" is chosen. */
	private Optional<Path> outputOverride() {
		return toFolder.isSelected() ? Optional.ofNullable(outputFolder) : Optional.empty();
	}

	private boolean missingOutputFolder() {
		return toFolder.isSelected() && outputFolder == null;
	}

	/** Plans again, e.g. after a batch, so slides converted meanwhile show as skipped (spec 08 acceptance). */
	void replan() {
		long generation = planGeneration.incrementAndGet();
		planning = true;
		updateControls();
		if (selections.isEmpty() || missingOutputFolder()) {
			apply(generation, new Plan(List.of(), List.of()));
			return;
		}
		List<Selection> requested = List.copyOf(selections);
		Optional<Path> override = outputOverride();
		planner.execute(() -> {
			if (planGeneration.get() != generation)
				return;
			Plan result;
			try {
				result = InputPlanner.plan(requested, override);
			} catch (RuntimeException e) {
				logger.warn("Planning failed", e);
				result = new Plan(List.of(), List.of(new Plan.Problem(
						UiText.get("ui.setup.plan-failed", e.getMessage()), List.of())));
			}
			Plan planned = result;
			Platform.runLater(() -> apply(generation, planned));
		});
	}

	private void apply(long generation, Plan result) {
		if (planGeneration.get() != generation)
			return;
		plan = result;
		planning = false;
		jobTable.getItems().setAll(result.jobs());
		problemBox.getChildren().clear();
		for (Plan.Problem problem : result.problems()) {
			var label = new Label(problem.message());
			label.setWrapText(true);
			label.setMinHeight(Region.USE_PREF_SIZE);
			label.setStyle(ConverterWindow.ERROR_STYLE);
			problemBox.getChildren().add(label);
		}
		problemBox.setVisible(!result.problems().isEmpty());
		updateControls();
	}

	private void updateControls() {
		boolean canStart = !planning && plan != null && !missingOutputFolder() && !selections.isEmpty()
				&& plan.canStart();
		start.setDisable(!canStart);
		hint.setText(UiText.startHint(plan, selections.isEmpty(), planning, missingOutputFolder()));
	}

	private void start() {
		if (start.isDisabled())
			return;
		ConversionSettings settings = advanced.settings(outputOverride().orElse(null));
		store.save(settings);
		onStart.accept(plan, settings);
	}
}
