package to.us.kevinraneri.picturematcher;

import static to.us.kevinraneri.picturematcher.PictureMatcher.removeFileExtension;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;

@Data
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
}
