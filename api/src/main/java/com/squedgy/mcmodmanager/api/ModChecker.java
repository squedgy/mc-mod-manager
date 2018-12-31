package com.squedgy.mcmodmanager.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.squedgy.mcmodmanager.AppLogger;
import com.squedgy.mcmodmanager.api.abstractions.CurseForgeResponse;
import com.squedgy.mcmodmanager.api.abstractions.ModVersion;
import com.squedgy.mcmodmanager.api.cache.CacheRetrievalException;
import com.squedgy.mcmodmanager.api.cache.Cacher;
import com.squedgy.mcmodmanager.api.cache.CachingFailedException;
import com.squedgy.mcmodmanager.api.response.CurseForgeResponseDeserializer;
import com.squedgy.mcmodmanager.api.response.ModIdFailedException;
import com.squedgy.mcmodmanager.api.response.ModIdNotFoundException;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public abstract class ModChecker {

	private static final String CACHE_BASE = "cache/",
		MOD_CACHE = CACHE_BASE + "mods/";
	private static boolean check = false,
		idChecked = false;
	private static String currentRead = "", currentWrite = "";

	public static String getCurrentRead() {
		return currentRead;
	}

	public static String getCurrentWrite() {
		return currentWrite;
	}


	public static CurseForgeResponse getForVersion(String mod, String version) throws ModIdNotFoundException, IOException {
		return get(mod, new CurseForgeResponseDeserializer(version));
	}

	public static CurseForgeResponse get(String mod) throws ModIdNotFoundException, IOException {
		return get(mod, new CurseForgeResponseDeserializer());
	}

	private static synchronized void setReadWrite(Runnable r) {
		r.run();
	}

	public static synchronized ModVersion getCurrentVersion(String mod, String mcVersion) throws CacheRetrievalException {
		ModVersion ret;
		while (currentWrite.equals(mod + "." + mcVersion)) ;
		try {
			setReadWrite(() -> currentRead = mod + "." + mcVersion);
			ret = Cacher.getInstance(mcVersion).getMod(mod);
		} catch (Exception e) {
			throw new CacheRetrievalException();
		} finally {
			setReadWrite(() -> currentRead = "");
		}
		if (ret == null) throw new CacheRetrievalException();
		return ret;
	}

	public static synchronized void writeCurrentVersion(ModVersion fromCurse, String mcVersion, String modId, String dotMinecraft) throws CachingFailedException {
		while (currentRead.equals(modId + "." + fromCurse)) ;
		try {
			setReadWrite(() -> currentWrite = modId + "." + fromCurse);
			File f = new File(dotMinecraft + File.separator + fromCurse.getFileName());
			if (f.exists()) {
				Cacher c = Cacher.getInstance(mcVersion);
				c.addMod(Cacher.getJarModId(new JarFile(f)), fromCurse);
			}
		} catch (Exception e) {
			AppLogger.error(e, ModChecker.class);
			throw new CachingFailedException();
		} finally {
			setReadWrite(() -> currentWrite = "");
		}
	}

	private static CurseForgeResponse get(String mod, CurseForgeResponseDeserializer deserializer) throws ModIdNotFoundException, IOException {

		URL url = new URL("https://api.cfwidget.com/minecraft/mc-mods/" + mod);
		HttpURLConnection con = (HttpURLConnection) url.openConnection();

		con.setRequestMethod("GET");
		int responseCode = con.getResponseCode();
		//If it's a 301 we should try with the new location
		if (responseCode == 301) {
			url = new URL(con.getHeaderField("location"));
			con = (HttpURLConnection) url.openConnection();
			responseCode = con.getResponseCode();
		}

		if (responseCode == 202 && !check) {
			try {
				TimeUnit.SECONDS.sleep(2);
			} catch (InterruptedException ignored) {
			}
			check = true;
			return get(mod, deserializer);
		} else if ((responseCode == 400 || responseCode == 404) && idChecked) {
			idChecked = false;
			throw new ModIdNotFoundException(mod);
		} else if (responseCode == 400 || responseCode == 404) {
			idChecked = true;
			throw new ModIdFailedException();
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
			ObjectMapper mapper = new ObjectMapper()
				.registerModule(
					new SimpleModule()
						.addDeserializer(CurseForgeResponse.class, deserializer)
				);
			return mapper
				.readValue(
					reader.lines()
						.collect(Collectors.joining(""))
						.replaceAll("\\n", "\\n")
						.replaceAll("\\r", "\\r"),
					CurseForgeResponse.class
				);
		} catch (FileNotFoundException e) {
			throw new ModIdNotFoundException(mod);
		} catch (Exception e) {
			throw new ModIdNotFoundException(String.format("Error with mod %s.", mod), e);
		} finally {
			check = false;
			idChecked = false;
		}
	}

	public static ModVersion getNewest(String mId, String mcV) throws ModIdNotFoundException {
		try {
			CurseForgeResponse resp = getForVersion(mId, mcV);

			ModVersion ret = resp
				.getVersions()
				.stream()
				.max(Comparator.comparing(ModVersion::getUploadedAt))
				.orElse(null);
			new ObjectMapper().writeValue(new File(System.getProperty("user.home") + File.separator + "checker-debug" + File.separator + mId + ".json"), resp);
			if (ret != null) return ret;
		} catch (Exception ex) {
		}
		throw new ModIdNotFoundException("Couldn't find the mod Id : " + mId + ". It's not cached and DOESN'T match a Curse Forge mod. Talk to the mod author about having the Id within their mcmod.info file match their Curse Forge mod id.");
	}

	public static boolean download(ModVersion v, String location, String mcVersion) {
		URL u;
		try {
			u = new URL(v.getDownloadUrl() + "/file");
		} catch (MalformedURLException e) {
			AppLogger.error(e, ModChecker.class);
			return false;
		}
		HttpsURLConnection connection;
		try {
			connection = (HttpsURLConnection) u.openConnection();
			connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:65.0) Gecko/20100101 Firefox/65.0");
			connection.setRequestMethod("GET");
			connection.setDoOutput(true);
			connection.connect();
			if (connection.getResponseCode() > 299 || connection.getResponseCode() < 200) {
				AppLogger.info("Couldn't access the url :" + v.getDownloadUrl() + "/file", ModChecker.class);
				return false;
			}

		} catch (IOException e) {
			AppLogger.error(e, ModChecker.class);
			return false;
		}

		boolean append = !v.getFileName().endsWith(".jar");
		String path = location + v.getFileName() + (append ? ".jar" : "");
		try (
			FileOutputStream outFile = new FileOutputStream(new File(path));
			ReadableByteChannel in = Channels.newChannel(connection.getInputStream());
			FileChannel out = outFile.getChannel()
		) {

			out.transferFrom(in, 0, Long.MAX_VALUE);
			connection.disconnect();
		} catch (IOException e) {
			AppLogger.error(e, ModChecker.class);
			return false;
		}
		return true;
	}

}
