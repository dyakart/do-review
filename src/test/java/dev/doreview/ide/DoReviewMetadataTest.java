package dev.doreview.ide;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.State;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/** Display branding may change without abandoning installed shortcuts or persisted review history. */
public final class DoReviewMetadataTest extends BasePlatformTestCase {
    public void testLoadedPluginUsesNewNameAndAuthorWithItsExistingIdentity() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.reviewmarks"));
        assertNotNull("The renamed plugin must remain an update of the installed plugin", plugin);
        assertEquals("DoReview", plugin.getName());
        assertEquals("Artem Dyakovich", plugin.getVendor());
        assertTrue(plugin.getDescription().contains("by Artem Dyakovich"));
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup("Review Marks"));
        assertEquals("DoReview", NotificationGroup.getGroupTitle("Review Marks"));
        var actions = ActionManager.getInstance();
        assertNotNull(actions.getAction("ReviewMarks.EditorGroup"));
        assertEquals("DoReview", actions.getAction("ReviewMarks.EditorGroup").getTemplatePresentation().getText());
        for (String id : List.of("ReviewMarks.Mark", "ReviewMarks.Clear", "ReviewMarks.Next", "ReviewMarks.Previous",
                "ReviewMarks.Coverage", "ReviewMarks.Changes", "ReviewMarks.AttachHistory", "ReviewMarks.ClearFile")) {
            assertNotNull("Existing user keymap action identity must remain registered: " + id, actions.getAction(id));
        }
    }

    public void testHistoryStorageAndWidgetIdentityRemainCompatibleWithTheNewBrand() {
        State state = ReviewHistoryStore.class.getAnnotation(State.class);
        assertNotNull(state);
        assertEquals("ReviewMarksHistory", state.name());
        assertEquals(1, state.storages().length);
        assertEquals("review-marks-history.xml", state.storages()[0].value());
        assertEquals(RoamingType.DISABLED, state.storages()[0].roamingType());
        assertFalse("Raw source snapshots must remain opaque to IDE path macros", state.storages()[0].usePathMacroManager());
        var widget = new ReviewCoverageWidgetFactory();
        assertEquals("ReviewMarks.Coverage", widget.getId());
        assertEquals("DoReview Coverage", widget.getDisplayName());
    }
}
