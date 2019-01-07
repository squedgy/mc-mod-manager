package com.squedgy.mcmodmanager.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.squedgy.mcmodmanager.AppLogger;
import com.squedgy.mcmodmanager.api.abstractions.CurseForgeResponse;
import com.squedgy.mcmodmanager.api.abstractions.ModVersion;
import com.squedgy.mcmodmanager.api.cache.CacheRetrievalException;
import com.squedgy.mcmodmanager.api.cache.Cacher;
import com.squedgy.mcmodmanager.api.cache.CachingFailedException;
import com.squedgy.mcmodmanager.api.cache.JsonModVersionDeserializer;
import com.squedgy.mcmodmanager.api.response.CurseForgeResponseDeserializer;
import com.squedgy.mcmodmanager.api.response.ModIdFoundConnectionFailed;
import com.squedgy.mcmodmanager.api.response.ModIdNotFoundException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.protocol.HttpContext;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class ModChecker {

	private static String currentRead = "", currentWrite = "";

	public static CurseForgeResponse getForVersion(String mod, String version) throws ModIdFoundConnectionFailed, IOException {
		return get(mod, new CurseForgeResponseDeserializer(version));
	}

	public static CurseForgeResponse get(String mod) throws ModIdFoundConnectionFailed, IOException {
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
			ret = Cacher.reading(Cacher.MOD_CACHE_DIRECTORY + mcVersion + ".json", new JsonModVersionDeserializer()).getItem(mod);
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
				Cacher c = Cacher.reading(Cacher.MOD_CACHE_DIRECTORY + mcVersion + ".json");
				c.putItem(modId, fromCurse);
				c.writeCache();
			}
		} catch (Exception e) {
			AppLogger.error(e, ModChecker.class);
			throw new CachingFailedException();
		} finally {
			setReadWrite(() -> currentWrite = "");
		}
	}

	private static CurseForgeResponse get(String mod, CurseForgeResponseDeserializer deserializer) throws IOException, ModIdFoundConnectionFailed {

		URL url = new URL("https://api.cfwidget.com/minecraft/mc-mods/" + mod);
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestMethod("GET");
		int responseCode = con.getResponseCode();

		if (responseCode >= 400 && responseCode < 500) {
			url = new URL("https://api.cfwidget.com/mc-mods/minecraft/" + mod);
			con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod("GET");
			responseCode = con.getResponseCode();
		}

		if (responseCode >= 400 && responseCode < 500) {
			url = new URL("https://api.cfwidget.com/projects/" + mod);
			con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod("GET");
			responseCode = con.getResponseCode();
		}

		if (responseCode >= 400 && responseCode < 500) throw new IOException(mod + " couldn't be found/accessed");

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
			throw new IOException(e.getMessage());
		} catch (Exception e) {
			throw new ModIdFoundConnectionFailed(String.format("Unknown Error with mod %s: %s", mod, e.getMessage()), e);
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
		try {
			String url = v.getDownloadUrl();
			int i = url.indexOf("://");
			String scheme = url.substring(0, i);
			url = url.substring(i + "://".length());
			int slash = url.indexOf('/');
			String host = url.substring(0, slash);
			String path = url.substring(slash) + "/file";

			System.out.println(scheme);
			System.out.println(host);
			System.out.println(path);

			HttpGet get = new HttpGet(new URI(scheme, host, path, null));
			HttpClient client = HttpClients.custom()
				.setRoutePlanner(new DefaultProxyRoutePlanner(new HttpHost("127.0.0.1", 8888, "http")))
				.setRedirectStrategy(new RedirectStrategy() {
					@Override
					public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return false;
					}

					@Override
					public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return null;
					}
				})
				.build();
			HttpResponse response = client.execute(get);
			System.out.println(response.getStatusLine());
			int responseCode = response.getStatusLine().getStatusCode();
			if (responseCode > 299 || responseCode < 200) {
				AppLogger.info("Couldn't access the url :" + response.getFirstHeader("Host"), ModChecker.class);
				return false;
			}

			boolean append = !v.getFileName().endsWith(".jar");
			String fileLocation = (location + v.getFileName() + (append ? ".jar" : ""));
			try (
				FileOutputStream outFile = new FileOutputStream(new File(fileLocation));
				ReadableByteChannel in = Channels.newChannel(response.getEntity().getContent());
				FileChannel out = outFile.getChannel()
			) {

				out.transferFrom(in, 0, Long.MAX_VALUE);
				return true;
			}
		} catch (Exception e) {
			AppLogger.error(e, ModChecker.class);
			return false;
		}
	}

	private static String getUserAgentForOs() {
		String os = System.getProperty("os.name");
		if (os.matches(".*[Ww]indows.*"))
			return "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:65.0) Gecko/20100101 Firefox/65.0";
		else if (os.matches(".*[Mm]ac [Oo][Ss].*"))
			return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.13; rv:65.0) Gecko/20100101 Firefox/65.0";
		else return "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:65.0) Gecko/20100101 Firefox/65.0";
	}

}
