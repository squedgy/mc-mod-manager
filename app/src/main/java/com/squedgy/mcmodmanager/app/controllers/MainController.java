package com.squedgy.mcmodmanager.app.controllers;

import com.squedgy.mcmodmanager.api.abstractions.ModVersion;
import com.squedgy.mcmodmanager.app.App;
import com.squedgy.mcmodmanager.app.components.DisplayVersion;
import com.squedgy.mcmodmanager.app.components.Modal;
import com.squedgy.mcmodmanager.app.threads.ModCheckingThread;
import com.squedgy.mcmodmanager.app.threads.ModInfoThread;
import com.squedgy.mcmodmanager.app.threads.ModLoadingThread;
import com.squedgy.mcmodmanager.app.util.JavafxUtils;
import com.squedgy.mcmodmanager.app.util.ModUtils;
import com.squedgy.mcmodmanager.app.util.PathUtils;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.util.Callback;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.squedgy.mcmodmanager.app.util.PathUtils.getResource;
import static org.slf4j.LoggerFactory.getLogger;

public class MainController {

	private static final Logger log = getLogger(MainController.class);
	private static final String TABLE_NAME = "home";
	private static MainController instance;
	private ModVersionTableController table;
	@FXML
	private MenuItem badJars;
	@FXML
	private WebView objectView;
	@FXML
	private GridPane listGrid;
	@FXML
	private VBox root;
	@FXML
	private MenuBar menu;
	private boolean filled = false;
	private ModCheckingThread checking;

	private MainController() throws IOException {
		FXMLLoader loader = new FXMLLoader(getResource("main.fxml"));
		loader.setController(this);
		loader.load();
	}

	public static MainController getInstance() throws IOException {
		if (instance == null) instance = new MainController();
		return instance;
	}

	@FXML
	public void initialize() {
		PathUtils.ensureMinecraftDirectory();
		objectView.cacheProperty().setValue(true);
		//Set the default view to a decent looking background
		updateObjectView("<h1>&nbsp;</h1>");
		objectView.getEngine().setJavaScriptEnabled(true);
		loadMods();
	}

	public void loadMods() {
		Platform.runLater(() ->{
			try {
				root.getChildren().setAll(new LoadingController().getRoot());
			} catch (IOException e) {
				log.error(e.getMessage(), getClass());
			}
		});

		ModLoadingThread t = new ModLoadingThread((mods) -> {
			Platform.runLater(() -> {
				try {
					initializeTable(mods);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				if(!filled){
					listGrid.add(table.getRoot(), 0, 0);
					filled = true;
				}
				badJars.setVisible(ModUtils.viewBadJars().size() > 0);
				root.getChildren().setAll(menu, listGrid);
				Platform.runLater(() -> App.getParent().getWindow().centerOnScreen());
			});
			return null;
		});
		t.start();
	}

	private void initializeTable(List<ModVersion> mods) throws IOException {
		if(table == null) table = new ModVersionTableController(TABLE_NAME, mods.toArray(new ModVersion[0]));
		else{
			ObservableList<DisplayVersion> observableList = FXCollections.observableArrayList(mods.stream().map(DisplayVersion::new).collect(Collectors.toList()));
			table.setItems(observableList);
		}
		if(!table.getColumns().get(0).getText().equals(" ")) {
			//Add the active/deactive image column here
			TableColumn<DisplayVersion, ImageView> col = new TableColumn<>();
			col.setText(" ");
			Callback<TableColumn.CellDataFeatures<DisplayVersion, ImageView>, ObservableValue<ImageView>> imageCellFactory = i -> new SimpleObjectProperty<>(i.getValue().getImage());
			col.setCellValueFactory(imageCellFactory);
			table.addColumn(0, col);
		}

		//The following two are made in code as
		//it could potentially not be necessary
		//for these to exist elsewhere
		//(De)activation toggling
		table.setOnDoubleClick(mod -> {
			ModUtils utils = ModUtils.getInstance();
			try {
				if (utils.modActive(mod)) {
					utils.deactivateMod(mod);
				} else {
					utils.activateMod(mod);
				}
			} catch (Exception e) {
				log.error("", e);
			}
			return null;
		});

		//Selection updating
		table.setOnChange((obs, old, neu) -> {
			if(neu == null) {
				updateObjectView("");
			}else if(neu.getDescription() == null) {
				updateObjectView("<h1>Loading...</h1>");
				ModInfoThread gathering = new ModInfoThread(
					neu,
					version -> Platform.runLater(() -> updateObjectView(version.getDescription())),
					() -> Platform.runLater(() -> updateObjectView(("<h2>Error Loading, couldn't find a description!</h2>")))
				);
				JavafxUtils.putSetterAndStart(objectView, gathering);
			}else{
				updateObjectView(neu.getDescription());
			}
		});
	}

	public VBox getRoot() {
		return root;
	}

	public List<DisplayVersion> getItems() {
		return table.getItems();
	}

	public void setItems(List<ModVersion> mods) {
		table.setItems(FXCollections.observableArrayList(mods.stream().map(DisplayVersion::new).collect(Collectors.toList())));
	}

	private synchronized void updateObjectView(String description) {
		objectView.getEngine().loadContent(
			"<style>" +
				"body{background-color:#303030; color:#ddd;}" +
				"img{max-width:100%;height:auto;}" +
				"a{color:#ff9000;text-decoration:none;} " +
				"a:visited{color:#544316;}" +
			"</style>" +
			"<script>" +
				"window.onload = function() { var tags = document.getElementsByTagName('iframe'); for(var i = tags.length-1; i >= 0; i++){ tags[i].parentNode.removeChild(tags[i]); }}" +
			"</script>" + description);
	}

	@FXML
	public void setColumns(Event e) {
		ModUtils.getInstance().CONFIG.writeColumnOrder(TABLE_NAME, table.getColumns());
	}

	public void updateModList() {
		setItems(Arrays.asList(ModUtils.getInstance().getMods()));
	}

	@FXML
	public void updateList(Event e){ updateModList(); }

	@FXML
	public void searchForUpdates(Event e) {
		Platform.runLater(() -> {
			Modal modal;
			try {

				modal = Modal.loading();
			} catch (IOException e1) {
				throw new RuntimeException();
			}

			if (checking == null || !checking.isAlive()) {

				checking = new ModCheckingThread(l -> {
					//do something with the returned list
					Platform.runLater(() -> {

						ModUpdaterController table;
						try {
							table = new ModUpdaterController(l);
							modal.setContent(table.getRoot());
							modal.openAndWait(App.getParent().getWindow());
						} catch (IOException e1) {
							log.error("", e1);
							modal.close();
						}
					});
					return null;
				});
				checking.start();
			}
		});
	}

	@FXML
	public void showBadJars(Event e)  {
		try {
			Modal m = Modal.getInstance(App.getParent().getWindow());
			BadJarsController c = new BadJarsController();
			m.setContent(c.getRoot());
			m.open(App.getParent().getWindow());
		}
		catch (IOException e1) { log.error(e1.getMessage(), getClass()); }
	}

	@FXML
	public void setJarIds(Event e){
		try {
			Modal m = Modal.getInstance(App.getParent().getWindow());
			SetJarIdController controller = new SetJarIdController();
			m.setContent(controller.getRoot());
			m.open(App.getParent().getWindow());
			m.setAfterClose(e2 -> {
								try {
					if(controller.isUpdated()) App.getInstance().getMainView().getRoot().getChildren().setAll(new LoadingController().getRoot());
					new Thread(() -> {
						if(controller.isUpdated()) {
							ModUtils utils = ModUtils.getInstance();
							log.debug("setting mods");
							utils.setMods();
							loadMods();
						}
						m.setAfterClose(null);
					}).start();
				} catch (IOException e1) {
					log.error("", e1);
				}
			});
		}
		catch (IOException e1) { log.error(e1.getMessage()); }


	}

	@FXML
	public void newMods(Event e) {
		try {
			Modal m = Modal.getInstance(App.getParent().getWindow());
			m.setContent(new NewModsController().getRoot());
			m.openAndWait(App.getParent().getWindow());
		} catch (IOException e1) {
			log.error(e1.getMessage(), getClass());
		}
	}

	@FXML
	public void mcVersion(Event e){
		try{
			Modal modal = Modal.getInstance(App.getParent().getWindow());
			modal.setContent(new MinecraftVersionController().getRoot());
			modal.openAndWait(App.getParent().getWindow());
		} catch (IOException e1) {
			log.error("", e1);
		}
	}

}
