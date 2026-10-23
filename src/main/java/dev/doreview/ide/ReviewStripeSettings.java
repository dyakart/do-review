package dev.doreview.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

/** Project-local presentation preference, independent of the review journal. */
@Service(Service.Level.PROJECT)
@com.intellij.openapi.components.State(name = "DoReviewStripeSettings",
        storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ReviewStripeSettings implements PersistentStateComponent<ReviewStripeSettings.State> {
    public interface Listener { void stripeVisibilityChanged(boolean shown); }
    public static final Topic<Listener> TOPIC = Topic.create("DoReview stripe visibility", Listener.class);

    private final Project project;
    private final Object lock = new Object();
    private volatile Value value = new Value(false);
    private boolean used;

    public ReviewStripeSettings(Project project) { this.project = project; }

    public static ReviewStripeSettings getInstance(Project project) {
        return project.getService(ReviewStripeSettings.class);
    }

    public boolean isShown() {
        synchronized (lock) {
            used = true;
            return value.shown;
        }
    }

    public void setShown(boolean shown) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        update(shown, true);
    }

    @Override public @NotNull State getState() {
        var state = new State();
        state.showReviewStripe = value.shown;
        return state;
    }

    @Override public void loadState(@NotNull State state) { update(state.showReviewStripe, false); }

    private void update(boolean shown, boolean userChange) {
        Value next;
        boolean notify;
        synchronized (lock) {
            used |= userChange;
            if (project.isDisposed() || value.shown == shown) return;
            value = next = new Value(shown);
            // Initial persistence loading must not initialize editor presentation.
            notify = used;
        }
        if (!notify) return;
        var application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) return;
        Runnable publish = () -> {
            if (!project.isDisposed() && value == next) {
                project.getMessageBus().syncPublisher(TOPIC).stripeVisibilityChanged(next.shown);
            }
        };
        if (application.isDispatchThread()) publish.run();
        else application.invokeLater(publish, project.getDisposed());
    }

    /** Identity also invalidates queued events after an intervening value change. */
    private record Value(boolean shown) { }

    public static final class State {
        public boolean showReviewStripe;
    }
}
