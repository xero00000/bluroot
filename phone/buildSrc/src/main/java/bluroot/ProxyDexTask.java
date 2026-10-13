package bluroot;

import java.io.File;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

public abstract class ProxyDexTask extends DefaultTask {
    @InputDirectory
    public abstract DirectoryProperty getClassesDir();

    @InputFile
    public abstract RegularFileProperty getAndroidJar();

    @InputFile
    public abstract RegularFileProperty getHalJar();

    @InputFile
    public abstract RegularFileProperty getD8();

    @InputFile
    public abstract RegularFileProperty getJarBin();

    @InputFile
    public abstract RegularFileProperty getScript();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Inject
    public abstract ExecOperations getExecOps();

    @TaskAction
    public void run() {
        File dexDir = new File(getTemporaryDir(), "dex");
        if (dexDir.exists()) {
            deleteAll(dexDir);
        }
        dexDir.mkdirs();
        File out = getOutputDir().get().getAsFile();
        out.mkdirs();
        getExecOps().exec(spec -> spec.commandLine(
                "bash",
                getScript().get().getAsFile().getAbsolutePath(),
                getClassesDir().get().getAsFile().getAbsolutePath(),
                dexDir.getAbsolutePath(),
                out.getAbsolutePath(),
                getAndroidJar().get().getAsFile().getAbsolutePath(),
                getD8().get().getAsFile().getAbsolutePath(),
                getHalJar().get().getAsFile().getAbsolutePath(),
                getJarBin().get().getAsFile().getAbsolutePath()
        ));
    }

    private static void deleteAll(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteAll(child);
            }
        }
        file.delete();
    }
}
