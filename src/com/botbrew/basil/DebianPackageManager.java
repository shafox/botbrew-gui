package com.botbrew.basil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class DebianPackageManager {
	private static final String TAG = "BB_PM";
	public static enum Config {
		APT_Architectures("APT::Architectures"),
		APT_IgnoreHold("APT::Ignore-Hold","0"),
		APT_CleanInstalled("APT::Clean-Installed","1"),
		APT_ForceLoopBreak("APT::Force-LoopBreak","0"),
		APT_CacheLimit("APT::CacheLimit"),
		APT_BuildEssential("APT::BuildEssential"),
		APT_Get_DownloadOnly("APT::Get::Download-Only","0"),
		APT_Get_FixBroken("APT::Get::Fix-Broken","0"),
		APT_Get_FixMissing("APT::Get::Fix-Missing","0"),
		APT_Get_Download("APT::Get::Download","1"),
		APT_Get_Simulate("APT::Get::Simulate","0"),
		APT_Get_AssumeYes("APT::Get::Assume-Yes","0"),
		APT_Get_ShowUpgraded("APT::Get::Show-Upgraded","1"),
		APT_Get_ShowVersions("APT::Get::Show-Versions","0"),
		APT_Get_Upgrade("APT::Get::Upgrade","1"),
		APT_Get_ForceYes("APT::Get::Force-Yes","0"),
		APT_Get_PrintURIs("APT::Get::Print-URIs","0"),
		APT_Get_ReInstall("APT::Get::ReInstall","0"),
		APT_Get_ListCleanup("APT::Get::List-Cleanup","1"),
		APT_DefaultRelease("APT::Default-Release"),
		APT_Get_TrivialOnly("APT::Get::Trivial-Only","0"),
		APT_Get_Remove("APT::Get::Remove","0"),
		APT_Get_Purge("APT::Get::Purge","0"),
		APT_Get_AllowUnauthenticated("APT::Get::AllowUnauthenticated","0"),
		DPkg_Options("DPkg::Options::");
		public final String name;
		public final String defval;
		Config(final String s) {
			this(s,"");
		}
		Config(final String s, final String d) {
			name = s.toLowerCase();
			defval = d;
		}
	}
	public static final String arch_native = Build.CPU_ABI.toLowerCase();
	public static final String arch =
		"device-"+Build.DEVICE.toLowerCase()+
		",cpu-abi-"+Build.CPU_ABI.toLowerCase()+
		",cpu-abi2-"+Build.CPU_ABI2.toLowerCase()+
		",board-"+Build.BOARD.toLowerCase()+
		",android"+
	//	("armeabi".equals(arch_native)?",armel":(","+arch_native))+
		","+Build.DEVICE.toLowerCase()+		// TODO: remove after dpkg transition
		","+Build.CPU_ABI.toLowerCase()+	// TODO: remove after dpkg transition
		","+Build.CPU_ABI2.toLowerCase();	// TODO: remove after dpkg transition
	private static Runtime runtime = Runtime.getRuntime();
	protected final EnumMap<Config,String> mConfig = new EnumMap<Config,String>(Config.class);
	public final String root;
	public boolean redirect = false;
	public String shell = "/system/bin/sh";
	public DebianPackageManager(final String root) {
		this.root = root;
	}
	public DebianPackageManager(final DebianPackageManager copy) {
		root = copy.root;
		redirect = copy.redirect;
		shell = copy.shell;
		config(copy.mConfig);
	}
	public void config(final EnumMap<Config,String> config) {
		mConfig.clear();
		mConfig.putAll(config);
	}
	public void config(final SharedPreferences pref) {
		mConfig.clear();
		for(Config key: Config.values()) {
			if(pref.contains(key.name)) try {
				config(key,pref.getBoolean(key.name,false)?"1":"0");
			} catch(ClassCastException ex0) {
				try {
					config(key,pref.getString(key.name,null));
				} catch(ClassCastException ex1) {}
			}
		}
		if(pref.getBoolean("debian_hack",false)) config(Config.DPkg_Options,"--ignore-depends=libgcc1");
		String var_pref = pref.getString("apt::architectures",null);
		config(Config.APT_Architectures,(var_pref==null)||("".equals(var_pref))?arch:arch+","+var_pref);
	}
	public String config(final Config key) {
		return mConfig.get(key);
	}
	public String config(final Config key, final String value) {
		return value==null?mConfig.remove(key):mConfig.put(key,value);
	}
	protected String mkConfigStr() {
		final StringBuilder sb = new StringBuilder();
		for(EnumMap.Entry<Config,String> entry: mConfig.entrySet()) {
			sb.append(" -o");
			sb.append(entry.getKey().name);
			sb.append("='");
			sb.append(entry.getValue());
			sb.append("'");
		}
		return sb.toString();
	}
	public String aptget_update() {
		return "apt-get"+mkConfigStr()+" update";
	}
	public String aptget_upgrade() {
		return "apt-get"+mkConfigStr()+" upgrade";
	}
	public String aptget_upgrade(final String pkg) {
		return "apt-get"+mkConfigStr()+" upgrade "+pkg;
	}
	public String aptget_distupgrade() {
		return "apt-get"+mkConfigStr()+" dist-upgrade";
	}
	public String aptget_distupgrade(final String pkg) {
		return "apt-get"+mkConfigStr()+" dist-upgrade "+pkg;
	}
	public String aptget_install(final String pkg) {
		return "apt-get"+mkConfigStr()+" install "+pkg;
	}
	public String aptget_remove(final String pkg) {
		return "apt-get"+mkConfigStr()+" remove "+pkg;
	}
	public String aptget_autoremove(final String pkg) {
		return "apt-get"+mkConfigStr()+" autoremove "+pkg;
	}
	public String aptget_clean() {
		return "apt-get"+mkConfigStr()+" clean";
	}
	public String aptget_autoclean() {
		return "apt-get"+mkConfigStr()+" autoclean";
	}
	public String aptcache_show(final String pkg) {
		return "apt-cache"+mkConfigStr()+" show '"+pkg+"'";
	}
	public String aptcache_search() {
		return "apt-cache"+mkConfigStr()+" search ''";
	}
	public String aptcache_search(final String q) {
		return "apt-cache"+mkConfigStr()+" search '"+q+"'";
	}
	public String dpkgquery(final String q) {
		return "dpkg-query "+q;
	}
	public boolean pm_update(final File tmpdir) {
		try {
			// set architectures
			File temp = File.createTempFile("botbrew-arch",".conf",tmpdir);
			FileWriter tempwriter = new FileWriter(temp);
			tempwriter.write(arch.replace(',','\n'));
			tempwriter.write('\n');
			tempwriter.close();
			final String archconf = "/var/lib/dpkg/arch";
			Process p = exec(true);
			final OutputStream p_stdin = p.getOutputStream();
			p_stdin.write(("cp '"+temp+"' '"+archconf+"' && chmod 0644 '"+archconf+"' && chown 0:0 '"+archconf+"'").getBytes());
			p_stdin.close();
			BotBrewApp.sinkOutput(p);
			BotBrewApp.sinkError(p);
			temp.delete();
			if(p.waitFor() != 0) return false;
			// update
			p = exec(true,aptget_update());
			p.getOutputStream().close();
			BotBrewApp.sinkOutput(p);
			BotBrewApp.sinkError(p);
			if(p.waitFor() != 0) return false;
			return true;
		} catch(IOException e) {
			Log.v(TAG,"IOException: cannot refresh database");
			return false;
		} catch(InterruptedException ex) {
			Log.v(TAG,"InterruptedException: cannot refresh database");
			return false;
		}
	}
	public boolean pm_refresh(final ContentResolver cr) {
		final ArrayList<ContentValues> values = new ArrayList<ContentValues>();
		try {
			ContentValues cv;
			Matcher matcher;
			String line;
			// installed packages
			final HashMap<String,ContentValues> installed = new HashMap<String,ContentValues>();
			Process p = exec(false,dpkgquery("--show --showformat='${status} ${package} ${version}\\n'"));
			p.getOutputStream().close();
			BufferedReader p_stdout = new BufferedReader(new InputStreamReader(p.getInputStream()));
			final Pattern re_name_version = Pattern.compile("^install ok installed (\\S+) (\\S+)$");
			while((line = p_stdout.readLine()) != null) {
				matcher = re_name_version.matcher(line);
				if(matcher.find()) {
					cv = new ContentValues();
					cv.put(DatabaseOpenHelper.C_INSTALLED,matcher.group(2));
					cv.put(DatabaseOpenHelper.C_UPGRADABLE,"");
					installed.put(matcher.group(1),cv);
				}
			}
			BotBrewApp.sinkError(p);
			if(p.waitFor() != 0) return false;
			// upgradable packages
			final DebianPackageManager dpm = new DebianPackageManager(this);
			dpm.config(Config.APT_Get_Simulate,"1");
			p = exec(false,dpm.aptget_distupgrade());
			p.getOutputStream().close();
			p_stdout = new BufferedReader(new InputStreamReader(p.getInputStream()));
			final Pattern re_inst_name_upgradable = Pattern.compile("^Inst (\\S+) \\[(\\S+)\\]");
			while((line = p_stdout.readLine()) != null) {
				matcher = re_inst_name_upgradable.matcher(line);
				if(matcher.find()) {
					line = matcher.group(1);
					cv = installed.get(line);
					if(cv != null) cv.put(DatabaseOpenHelper.C_UPGRADABLE,matcher.group(2));
				}
			}
			BotBrewApp.sinkError(p);
			if(p.waitFor() != 0) return false;
			// available packages
			p = exec(false,aptcache_search());
			p.getOutputStream().close();
			p_stdout = new BufferedReader(new InputStreamReader(p.getInputStream()));
			final Pattern re_name_summary = Pattern.compile("^(\\S+) - (.*)$");
			while((line = p_stdout.readLine()) != null) {
				matcher = re_name_summary.matcher(line);
				if(!matcher.find()) continue;
				line = matcher.group(1);
				cv = installed.get(line);
				if(cv == null) {
					cv = new ContentValues();
					cv.put(DatabaseOpenHelper.C_INSTALLED,"");
					cv.put(DatabaseOpenHelper.C_UPGRADABLE,"");
				}
				cv.put(DatabaseOpenHelper.C_NAME,line);
				cv.put(DatabaseOpenHelper.C_SUMMARY,matcher.group(2));
				values.add(cv);
			}
			BotBrewApp.sinkError(p);
			if(p.waitFor() != 0) return false;
		} catch(IOException e) {
			Log.v(TAG,"IOException: cannot refresh database");
			return false;
		} catch(InterruptedException ex) {
			Log.v(TAG,"InterruptedException: cannot refresh database");
			return false;
		}
		final ContentValues[] a = new ContentValues[values.size()];
		values.toArray(a);
		cr.bulkInsert(PackageCacheProvider.ContentUri.CACHE_BASE.uri,a);
		return true;
	}
	protected Process exec(final boolean superuser) throws IOException {
		return runtime.exec(new String[] {superuser?"/system/xbin/su":shell});
	}
	protected Process exec(final boolean superuser, final CharSequence command) throws IOException {
		final Process p = exec(superuser);
		final StringBuilder sb = new StringBuilder();
		sb.append("exec ");
		sb.append(root);
		sb.append("/init -- ");
		sb.append(command);
		if(redirect) sb.append(" 2>&1");
		p.getOutputStream().write(sb.toString().getBytes());
		return p;
	}
}
