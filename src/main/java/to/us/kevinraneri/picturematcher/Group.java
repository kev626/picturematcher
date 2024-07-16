package to.us.kevinraneri.picturematcher;

import static to.us.kevinraneri.picturematcher.PictureMatcher.removeFileExtension;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Group {
	
	private byte[] baseHash;
	private byte[] sonyHash;
	
	private boolean ignored = false;
	
	private Set<File> files = new HashSet<>();
	
	public boolean matchesBase(byte[] hash) {
		return Arrays.equals(baseHash, hash);
	}
	
	public boolean matchesSony(byte[] hash) {
		return Arrays.equals(sonyHash, hash);
	}
	
	public String getFinalName() {
		int deepestFileDirs = 0;
		File deepestFile = null;
		for (File file : getFiles()) {
			int dirs = 1;
			File parent = file.getParentFile();
			while (parent != null) {
				dirs++;
				parent = parent.getParentFile();
			}
			if (dirs > deepestFileDirs) {
				deepestFileDirs = dirs;
				deepestFile = file;
			}
		}
		
		return removeFileExtension(deepestFile.getName());
	}

	public byte[] getBaseHash() {
		return baseHash;
	}

	public void setBaseHash(byte[] baseHash) {
		this.baseHash = baseHash;
	}

	public byte[] getSonyHash() {
		return sonyHash;
	}

	public void setSonyHash(byte[] sonyHash) {
		this.sonyHash = sonyHash;
	}

	public boolean isIgnored() {
		return ignored;
	}

	public void setIgnored(boolean ignored) {
		this.ignored = ignored;
	}

	public Set<File> getFiles() {
		return files;
	}
}
