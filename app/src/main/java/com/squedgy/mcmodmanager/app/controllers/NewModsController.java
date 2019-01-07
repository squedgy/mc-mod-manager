package com.squedgy.mcmodmanager.app.controllers;

import com.squedgy.mcmodmanager.api.ModChecker;
import com.squedgy.mcmodmanager.api.abstractions.ModVersion;
import com.squedgy.mcmodmanager.api.response.ModIdFoundConnectionFailed;
import com.squedgy.mcmodmanager.api.response.ModIdNotFoundException;
import com.squedgy.mcmodmanager.app.util.ModUtils;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static com.squedgy.mcmodmanager.app.Startup.*;

public class NewModsController {

	@FXML
	public VBox root;
	@FXML
	public TextArea mods;

	public NewModsController() throws IOException {
		FXMLLoader loader = new FXMLLoader(getResource("components" + File.separator + "new-mods.fxml"));
		loader.setController(this);
		loader.load();
	}

	@FXML
	public void initialize(){ }

	@FXML
	public void addMods(Event e){
		String[] ids = mods.getText().split("([^-a-zA-Z0-9]|\n|\r\n|\r)+");
		for(String id : ids){
			ModVersion v = null;
			try {
				v = ModChecker.getNewest(id, MINECRAFT_VERSION);
				if(ModChecker.download(v, DOT_MINECRAFT_LOCATION + File.separator + "mods", MINECRAFT_VERSION)){

				}

			}catch(ModIdNotFoundException ignored){}
			if( v !=null ) ModUtils.getInstance().addMod(id, v);
		}
	}

	public VBox getRoot() { return root; }

}
