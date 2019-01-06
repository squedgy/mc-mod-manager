package com.squedgy.mcmodmanager.app.components;


import javafx.beans.value.ObservableNumberValue;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.io.IOException;

import static com.squedgy.mcmodmanager.app.Startup.getResource;

public class Modal {

	@FXML
	public ScrollPane root;
	@FXML
	public VBox holder;

	private Stage stage;

	private static Modal instance;

	public static Modal getInstance() throws IOException {
		if(instance == null) instance = new Modal();
		instance.setAfterClose(e -> {});
		return instance;
	}

	private Modal() throws IOException {
		FXMLLoader loader = new FXMLLoader(getResource("components/modal.fxml"));
		loader.setController(this);
		loader.load();
		holder.prefWidthProperty().bind(root.widthProperty().subtract(2));
		holder.prefHeightProperty().bind(root.heightProperty().subtract(2));
		root.minWidthProperty().setValue(500);
		root.minHeightProperty().setValue(300);
		holder.setPadding(new Insets(5,5,5,5));
	}

	public void setContent(Control node) {
		holder.getChildren().setAll(node);
		node.prefWidthProperty().bind(holder.widthProperty());
		node.prefHeightProperty().bind(holder.heightProperty());
	}

	public void setContent(Region node) {
		holder.getChildren().setAll(node);
		node.prefWidthProperty().bind(holder.widthProperty());
		node.prefHeightProperty().bind(holder.heightProperty());
	}

	public void setContent(WebView node) {
		holder.getChildren().setAll(node);
		node.prefWidthProperty().bind(holder.widthProperty());
		node.prefHeightProperty().bind(holder.heightProperty());
	}

	public void bindMinHeight(ObservableNumberValue v){
		root.minHeightProperty().bind(v);
	}

	public void bindMinWidth(ObservableNumberValue v){
		root.minWidthProperty().bind(v);
	}

	public ScrollPane getRoot() { return root; }

	public void open(Window owner) {
		setUp(owner);
		stage.show();
	}

	public void setAfterClose(EventHandler<WindowEvent> e){
		if(stage != null) stage.onCloseRequestProperty().setValue(e);
	}

	public void openAndWait(Window window) {
		setUp(window);
		stage.showAndWait();
	}

	private void setUp(Window window) {
		if (stage == null) {
			stage = new Stage();
			Scene scene = new Scene(root);
			scene.setRoot(root);
			stage.setScene(scene);
			stage.setMinHeight(root.getMinHeight());
			stage.setMinWidth(root.getMinWidth());
			stage.initOwner(window);
			stage.initModality(Modality.APPLICATION_MODAL);
		}

	}

	public void close() {
		stage.close();
	}

}