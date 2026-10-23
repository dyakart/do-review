package dev.doreview.ide;

import com.intellij.mock.MockProject;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.xmlb.XmlSerializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Workspace preference persistence and registered project-wide toggle behavior. */
public final class ReviewStripeSettingsTest extends BasePlatformTestCase {
    private ReviewStripeSettings settings;

    @Override protected CodeInsightTestFixture createMyFixture() {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        IdeaProjectTestFixture project = factory.createFixtureBuilder(getTestName(false)).getFixture();
        return factory.createCodeInsightFixture(project, new TempDirTestFixtureImpl() {
            @Override protected Path getTempHome() { return Path.of(project.getProject().getBasePath()); }
        });
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        settings = ReviewStripeSettings.getInstance(getProject());
        settings.loadState(new ReviewStripeSettings.State());
    }

    public void testStripeIsHiddenByDefaultAndStoredInWorkspace() {
        assertFalse(settings.isShown());
        assertFalse(settings.getState().showReviewStripe);
        var annotation = ReviewStripeSettings.class.getAnnotation(com.intellij.openapi.components.State.class);
        assertEquals("DoReviewStripeSettings", annotation.name());
        assertEquals(StoragePathMacros.WORKSPACE_FILE, annotation.storages()[0].value());
    }

    public void testStateRoundTripUsesDetachedBeans() {
        var exported = settings.getState();
        exported.showReviewStripe = true;
        assertFalse("Changing an exported bean cannot change the live preference", settings.isShown());
        settings.loadState(exported);
        exported.showReviewStripe = false;
        assertTrue("Loading a bean cannot retain its mutable identity", settings.isShown());

        var serialized = XmlSerializer.serialize(settings.getState());
        var loaded = XmlSerializer.deserialize(serialized, ReviewStripeSettings.State.class);
        var reopened = new ReviewStripeSettings(getProject());
        reopened.loadState(loaded);
        loaded.showReviewStripe = false;
        assertTrue(reopened.isShown());
        assertNotSame(reopened.getState(), reopened.getState());
        reopened.loadState(new ReviewStripeSettings.State());
        assertFalse(reopened.isShown());
    }

    public void testVisibilityEventsStayInTheirProjectAndSkipNoOps() {
        var events = new ArrayList<Boolean>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(ReviewStripeSettings.TOPIC, shown -> {
            assertTrue("Visibility changes are delivered on EDT", ApplicationManager.getApplication().isDispatchThread());
            events.add(shown);
        });
        var otherProject = new MockProject(null, getTestRootDisposable());
        var otherSettings = new ReviewStripeSettings(otherProject);
        var otherEvents = new ArrayList<Boolean>();
        otherProject.getMessageBus().connect(getTestRootDisposable()).subscribe(ReviewStripeSettings.TOPIC, otherEvents::add);

        settings.setShown(true);
        settings.setShown(true);
        assertEquals(List.of(true), events);
        assertEmpty(otherEvents);
        assertFalse(otherSettings.isShown());
        otherSettings.setShown(true);
        assertEquals(List.of(true), events);
        assertEquals(List.of(true), otherEvents);
        settings.setShown(false);
        assertEquals(List.of(true, false), events);
        assertTrue(otherSettings.isShown());
    }

    public void testInitialStateLoadDoesNotPublishButRuntimeReloadDoes() {
        var initial = new ReviewStripeSettings(getProject());
        var events = new ArrayList<Boolean>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(ReviewStripeSettings.TOPIC, shown -> {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            events.add(shown);
        });
        var state = new ReviewStripeSettings.State();
        state.showReviewStripe = true;
        initial.loadState(state);
        assertEmpty(events);
        assertTrue(initial.isShown());
        initial.loadState(new ReviewStripeSettings.State());
        initial.loadState(state);
        initial.loadState(state);
        assertEquals(List.of(false, true), events);
    }

    public void testBackgroundReloadPublishesOnEdtAndSkipsSupersededValues() throws Exception {
        assertFalse(settings.isShown());
        var events = new ArrayList<Boolean>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(ReviewStripeSettings.TOPIC, shown -> {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            events.add(shown);
        });
        var visible = new ReviewStripeSettings.State();
        visible.showReviewStripe = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> settings.loadState(visible)).get(10, TimeUnit.SECONDS);
        assertEmpty(events);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertEquals(List.of(true), events);
        ApplicationManager.getApplication().executeOnPooledThread(() -> settings.loadState(new ReviewStripeSettings.State()))
                .get(10, TimeUnit.SECONDS);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertEquals(List.of(true, false), events);

        ApplicationManager.getApplication().executeOnPooledThread(() -> settings.loadState(visible)).get(10, TimeUnit.SECONDS);
        settings.setShown(false);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        assertEquals("A queued prior value must not re-show a stripe after a newer change", List.of(true, false, false), events);
        assertFalse(settings.isShown());
    }

    public void testRegisteredToggleWorksWithOnlyProjectContextAndHasNoShortcut() {
        var action = ActionManager.getInstance().getAction("ReviewMarks.ToggleStripe");
        assertTrue(action instanceof ToggleReviewStripeAction);
        assertEquals("Show DoReview Stripe", action.getTemplatePresentation().getText());
        assertEquals(ActionUpdateThread.EDT, action.getActionUpdateThread());
        assertEquals(0, action.getShortcutSet().getShortcuts().length);
        var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).build();
        var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context);
        action.update(event);
        assertTrue(event.getPresentation().isEnabledAndVisible());
        var toggle = (ToggleReviewStripeAction) action;
        assertFalse(toggle.isSelected(event));
        toggle.setSelected(event, true);
        assertTrue(settings.isShown());
        assertTrue(toggle.isSelected(event));
        toggle.setSelected(event, false);
        assertFalse(settings.isShown());
    }

    public void testToggleIsUnavailableWithoutProject() {
        var action = new ToggleReviewStripeAction();
        var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, DataContext.EMPTY_CONTEXT);
        action.update(event);
        assertFalse(event.getPresentation().isEnabledAndVisible());
        action.setSelected(event, true);
        assertFalse(settings.isShown());
    }
}
