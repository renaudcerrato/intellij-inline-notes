package inlinenotes;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

public final class InlineNotesStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(Project project) {
        Runnable initialize = () -> {
            if (!project.isDisposed()) project.getService(NoteStore.class).initialize();
        };
        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) initialize.run();
        else application.invokeAndWait(initialize);
    }
}
