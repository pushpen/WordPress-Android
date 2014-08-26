package org.wordpress.android.ui.notifications;

import android.app.ActionBar;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.AbsListView;

import com.simperium.client.Bucket;
import com.simperium.client.BucketObjectMissingException;

import org.wordpress.android.GCMIntentService;
import org.wordpress.android.R;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.models.Note;
import org.wordpress.android.ui.AuthenticatedWebViewActivity;
import org.wordpress.android.ui.WPActionBarActivity;
import org.wordpress.android.ui.comments.CommentActions;
import org.wordpress.android.ui.comments.CommentDetailFragment;
import org.wordpress.android.ui.comments.CommentDialogs;
import org.wordpress.android.ui.notifications.utils.SimperiumUtils;
import org.wordpress.android.ui.reader.ReaderPostDetailFragment;
import org.wordpress.android.ui.reader.ReaderPostListFragment;
import org.wordpress.android.ui.reader.actions.ReaderAuthActions;
import org.wordpress.android.ui.stats.StatsActivity;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.AuthenticationDialogUtils;
import org.wordpress.android.util.DisplayUtils;

public class NotificationsActivity extends WPActionBarActivity
        implements CommentActions.OnCommentChangeListener, NotificationFragment.OnPostClickListener,
        NotificationFragment.OnCommentClickListener {
    public static final String NOTIFICATION_ACTION = "org.wordpress.android.NOTIFICATION";
    public static final String NOTE_ID_EXTRA = "noteId";
    public static final String FROM_NOTIFICATION_EXTRA = "fromNotification";
    public static final String NOTE_INSTANT_REPLY_EXTRA = "instantReply";
    public static final int NOTIFICATION_TRANSITION_DURATION = 300;
    private static final String KEY_INITIAL_UPDATE = "initial_update";
    private static final String TAG_LIST_VIEW = "listView";
    private static final String TAG_DETAIL_VIEW = "detailView";

    private NotificationsListFragment mNotesList;
    private Fragment mDetailFragment;
    private String mSelectedNoteId;
    private boolean mHasPerformedInitialUpdate;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        createMenuDrawer(R.layout.notifications_activity);

        if (savedInstanceState == null) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.NOTIFICATIONS_ACCESSED);
        }

        FragmentManager fm = getFragmentManager();
        if (fm.findFragmentByTag(TAG_LIST_VIEW) != null) {
            mNotesList = (NotificationsListFragment)fm.findFragmentByTag(TAG_LIST_VIEW);
        } else {
            mNotesList = new NotificationsListFragment();
            FragmentTransaction fragmentTransaction = fm.beginTransaction();
            fragmentTransaction.add(R.id.layout_fragment_container, mNotesList, TAG_LIST_VIEW);
            fragmentTransaction.commit();
        }

        if (DisplayUtils.isLandscapeTablet(this)) {
            if (fm.findFragmentByTag(TAG_DETAIL_VIEW) != null) {
                mDetailFragment = fm.findFragmentByTag(TAG_DETAIL_VIEW);
            } else {
                addDetailFragment();
            }
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(true);
        }
        setTitle(getResources().getString(R.string.notifications));


        fm.addOnBackStackChangedListener(mOnBackStackChangedListener);
        mNotesList.setOnNoteClickListener(new NoteClickListener());

        GCMIntentService.clearNotificationsMap();

        if (savedInstanceState != null) {
            mHasPerformedInitialUpdate = savedInstanceState.getBoolean(KEY_INITIAL_UPDATE);
        } else {
            launchWithNoteId();
        }

        // remove window background since background color is set in fragment (reduces overdraw)
        getWindow().setBackgroundDrawable(null);

        // Show an auth alert if we don't have an authorized Simperium user
        if (SimperiumUtils.isUserNotAuthorized()) {
            AuthenticationDialogUtils.showAuthErrorDialog(this, R.string.sign_in_again,
                    R.string.simperium_connection_error);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        GCMIntentService.clearNotificationsMap();
        launchWithNoteId();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Remove notification if it is showing when we resume this activity.
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(GCMIntentService.PUSH_NOTIFICATION_ID);

        if (SimperiumUtils.isUserAuthorized()) {
            SimperiumUtils.startBuckets();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // When rotating on a tablet, we'll add or remove the detail view
        if (DisplayUtils.isTablet(this)) {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                FragmentManager fm = getFragmentManager();
                // pop back to list view fragment
                fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                mMenuDrawer.setDrawerIndicatorEnabled(true);
                mNotesList.getListView().setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
                // Add the note detail fragment
                addDetailFragment();
                if (mSelectedNoteId != null) {
                    openNoteForNoteId(mSelectedNoteId);
                }
                mNotesList.setSelectedPositionChecked();
                mNotesList.refreshNotes();
            } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                if (mNotesList != null) {
                    mNotesList.getListView().setChoiceMode(AbsListView.CHOICE_MODE_NONE);
                    mNotesList.resetSelection();
                }
                // Remove the detail fragment when rotating back to portrait
                if (mDetailFragment != null) {
                    FragmentManager fm = getFragmentManager();
                    FragmentTransaction ft = fm.beginTransaction();
                    ft.remove(mDetailFragment);
                    mDetailFragment = null;
                    ft.commitAllowingStateLoss();
                    fm.executePendingTransactions();
                }
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                if (DisplayUtils.isLandscapeTablet(this)) {
                    // let WPActionBarActivity handle it (toggles menu drawer)
                    return super.onOptionsItemSelected(item);
                } else {
                    FragmentManager fm = getFragmentManager();
                    if (fm.getBackStackEntryCount() > 0) {
                        popNoteDetail();
                        return true;
                    } else {
                        return super.onOptionsItemSelected(item);
                    }
                }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.notifications, menu);
        return true;
    }

    private void addDetailFragment() {
        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        mDetailFragment = new NotificationsDetailListFragment();
        ft.add(R.id.layout_fragment_container, mDetailFragment, TAG_DETAIL_VIEW);
        ft.commitAllowingStateLoss();
        fm.executePendingTransactions();
    }

    private void removeDetailFragment() {
        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.remove(mDetailFragment);
        ft.commitAllowingStateLoss();
        fm.executePendingTransactions();
    }

    private final FragmentManager.OnBackStackChangedListener mOnBackStackChangedListener =
            new FragmentManager.OnBackStackChangedListener() {
                public void onBackStackChanged() {
                    int backStackEntryCount = getFragmentManager().getBackStackEntryCount();
                    if (backStackEntryCount == 0) {
                        mMenuDrawer.setDrawerIndicatorEnabled(true);
                        setTitle(R.string.notifications);
                    } else {
                        mMenuDrawer.setDrawerIndicatorEnabled(false);
                    }
                }
            };

    /**
     * Detect if Intent has a noteId extra and display that specific note detail fragment
     */
    private void launchWithNoteId() {
        Intent intent = getIntent();
        if (intent.hasExtra(NOTE_ID_EXTRA)) {
            openNoteForNoteId(intent.getStringExtra(NOTE_ID_EXTRA));
        } else {
            // Dual pane and no note specified then select the first note
            if (DisplayUtils.isLandscapeTablet(this) && mNotesList != null) {
                mNotesList.setShouldLoadFirstNote(true);
            }
        }
    }

    private void openNoteForNoteId(String noteId) {
        Bucket<Note> notesBucket = SimperiumUtils.getNotesBucket();
        try {
            if (notesBucket != null) {
                Note note = notesBucket.get(noteId);
                if (note != null) {
                    openNote(note, 0);
                }
            }
        } catch (BucketObjectMissingException e) {
            AppLog.e(T.NOTIFS, "Could not load notification from bucket.");
        }
    }

    /*
     * triggered from the comment details fragment whenever a comment is changed (moderated, added,
     * deleted, etc.) - refresh notifications so changes are reflected here
     */
    @Override
    public void onCommentChanged(CommentActions.ChangedFrom changedFrom, CommentActions.ChangeType changeType) {
        // remove the comment detail fragment if the comment was trashed
        if (changeType == CommentActions.ChangeType.TRASHED && changedFrom == CommentActions.ChangedFrom.COMMENT_DETAIL) {
            FragmentManager fm = getFragmentManager();
            if (fm.getBackStackEntryCount() > 0) {
                fm.popBackStack();
            }
        }

        mNotesList.refreshNotes();
    }

    void popNoteDetail() {
        FragmentManager fm = getFragmentManager();
        fm.popBackStack();
    }

    /**
     * Tries to pick the correct fragment detail type for a given note
     * Defaults to NotificationDetailListFragment
     */
    private Fragment getDetailFragmentForNote(Note note) {
        if (note == null)
            return null;

        Fragment fragment;
        if (note.isCommentType()) {
            // show comment detail for comment notifications
            fragment = CommentDetailFragment.newInstance(note);
        } else if (note.isAutomattcherType()) {
            // show reader post detail for automattchers about posts - note that comment
            // automattchers are handled by note.isCommentType() above
            boolean isPost = (note.getBlogId() != 0 && note.getPostId() != 0 && note.getCommentId() == 0);
            if (isPost) {
                fragment = ReaderPostDetailFragment.newInstance(note.getBlogId(), note.getPostId());
            } else {
                fragment = NotificationsDetailListFragment.newInstance(note);
            }
        } else {
            fragment = NotificationsDetailListFragment.newInstance(note);
        }

        return fragment;
    }

    /**
     * Open a note fragment based on the type of note
     */
    private void openNote(final Note note, float yOffset) {
        if (note == null || isFinishing() || isActivityDestroyed()) {
            return;
        }

        mSelectedNoteId = note.getId();

        // mark the note as read if it's unread
        if (note.isUnread()) {
            // mark as read which syncs with simperium
            note.markAsRead();
        }

        // If we are already showing the NotificationDetailListFragment on a tablet, update note.
        if (DisplayUtils.isLandscapeTablet(this)) {
            NotificationsDetailListFragment detailListFragment = (NotificationsDetailListFragment) mDetailFragment;
            detailListFragment.setNote(note);
            detailListFragment.reloadNoteBlocks();
            return;
        } else if (DisplayUtils.isLandscapeTablet(this)) {
            removeDetailFragment();
        }

        // create detail fragment for this note type
        mDetailFragment = getDetailFragmentForNote(note);

        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        ft.replace(R.id.layout_fragment_container, mDetailFragment);
        mMenuDrawer.setDrawerIndicatorEnabled(false);
        ft.addToBackStack(null);
        ft.commitAllowingStateLoss();

        // Update title
        if (note.getFormattedSubject() != null) {
            setTitle(note.getTitle());
        }
    }

    public void showBlogPreviewForSiteId(long siteId, String siteUrl) {
        ReaderPostListFragment readerPostListFragment = ReaderPostListFragment.newInstance(siteId, siteUrl);
        FragmentManager fm = getFragmentManager();

        FragmentTransaction ft = fm.beginTransaction();
        if (mDetailFragment != null) {
            ft.hide(mDetailFragment);
        }
        ft.add(R.id.layout_fragment_container, readerPostListFragment).setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        ft.addToBackStack(null);
        ft.commitAllowingStateLoss();
    }

    public void showPostForSiteAndPostId(long siteId, long postId) {
        ReaderPostDetailFragment readerPostDetailFragment = ReaderPostDetailFragment.newInstance(siteId, postId);
        FragmentManager fm = getFragmentManager();

        FragmentTransaction ft = fm.beginTransaction();
        if (mDetailFragment != null) {
            ft.hide(mDetailFragment);
        }
        ft.add(R.id.layout_fragment_container, readerPostDetailFragment).setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        ft.addToBackStack(null);
        ft.commitAllowingStateLoss();
    }

    public void showWebViewActivityForUrl(String url) {
        Intent intent = new Intent(this, AuthenticatedWebViewActivity.class);
        intent.putExtra("url", url);
        startActivity(intent);
    }

    private class NoteClickListener implements NotificationsListFragment.OnNoteClickListener {
        @Override
        public void onClickNote(Note note, float yPosition) {
            if (note == null)
                return;
            // open the latest version of this note just in case it has changed - this can
            // happen if the note was tapped from the list fragment after it was updated
            // by another fragment (such as NotificationCommentLikeFragment)
            openNote(note, yPosition);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (outState.isEmpty()) {
            outState.putBoolean("bug_19917_fix", true);
        }
        outState.putBoolean(KEY_INITIAL_UPDATE, mHasPerformedInitialUpdate);
        outState.putString(NOTE_ID_EXTRA, mSelectedNoteId);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mHasPerformedInitialUpdate) {
            mHasPerformedInitialUpdate = true;
            ReaderAuthActions.updateCookies(this);
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        Dialog dialog = CommentDialogs.createCommentDialog(this, id);
        if (dialog != null)
            return dialog;
        return super.onCreateDialog(id);
    }

    /**
     * called from fragment when a link to a post is tapped - shows the post in a reader
     * detail fragment
     */
    @Override
    public void onPostClicked(Note note, int remoteBlogId, int postId) {
        ReaderPostDetailFragment readerFragment = ReaderPostDetailFragment.newInstance(remoteBlogId, postId);
        String tagForFragment = getString(R.string.fragment_tag_reader_post_detail);
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.layout_fragment_container, readerFragment, tagForFragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .addToBackStack(tagForFragment)
                .commit();
    }

    /**
     * called from fragment when a link to a comment is tapped - shows the comment in the comment
     * detail fragment
     */
    @Override
    public void onCommentClicked(Note note, int remoteBlogId, long commentId) {
        CommentDetailFragment commentFragment = CommentDetailFragment.newInstance(note);
        String tagForFragment = getString(R.string.fragment_tag_comment_detail);
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.layout_fragment_container, commentFragment, tagForFragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .addToBackStack(tagForFragment)
                .commit();
    }
}
