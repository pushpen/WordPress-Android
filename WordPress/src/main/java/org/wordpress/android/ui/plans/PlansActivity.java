package org.wordpress.android.ui.plans;

import android.animation.Animator;
import android.annotation.TargetApi;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.wordpress.android.BuildConfig;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.ui.plans.models.Plan;
import org.wordpress.android.ui.plans.models.SitePlan;
import org.wordpress.android.ui.plans.util.IabHelper;
import org.wordpress.android.ui.plans.util.IabResult;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.widgets.WPViewPager;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import de.greenrobot.event.EventBus;

public class PlansActivity extends AppCompatActivity {

    public static final String ARG_LOCAL_TABLE_BLOG_ID = "ARG_LOCAL_TABLE_BLOG_ID";
    private static final String ARG_LOCAL_AVAILABLE_PLANS = "ARG_LOCAL_AVAILABLE_PLANS";
    private static final String SAVED_VIEWPAGER_POS = "SAVED_VIEWPAGER_POS";

    private static final int NO_PREV_POS_SELECTED_VIEWPAGER = -1;

    private int mLocalBlogID = -1;
    private SitePlan[] mAvailablePlans;
    private int mViewpagerPosSelected = NO_PREV_POS_SELECTED_VIEWPAGER;

    private WPViewPager mViewPager;
    private PlansPageAdapter mPageAdapter;
    private TabLayout mTabLayout;

    private IabHelper mIabHelper;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.plans_activity);

        if (savedInstanceState != null) {
            mLocalBlogID = savedInstanceState.getInt(ARG_LOCAL_TABLE_BLOG_ID);
            Serializable serializable = savedInstanceState.getSerializable(ARG_LOCAL_AVAILABLE_PLANS);
            if (serializable instanceof SitePlan[]) {
                mAvailablePlans = (SitePlan[]) serializable;
            }
            mViewpagerPosSelected = savedInstanceState.getInt(SAVED_VIEWPAGER_POS, NO_PREV_POS_SELECTED_VIEWPAGER);
        } else if (getIntent() != null) {
            mLocalBlogID = getIntent().getIntExtra(ARG_LOCAL_TABLE_BLOG_ID, -1);
        }

        if (WordPress.getBlog(mLocalBlogID) == null) {
            AppLog.e(AppLog.T.STATS, "The blog with local_blog_id " + mLocalBlogID + " cannot be loaded from the DB.");
            Toast.makeText(this, R.string.plans_loading_error, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mViewPager = (WPViewPager) findViewById(R.id.viewpager);
        mTabLayout = (TabLayout) findViewById(R.id.tab_layout);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Shadow removed on Activities with a tab toolbar
            actionBar.setTitle(getString(R.string.plans));
            actionBar.setElevation(0.0f);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updatePurchaseUI(position);
            }
        });

        if (AppPrefs.isInAppBillingAvailable()) {
            startInAppBillingHelper();
        }
    }

    @Override
    protected void onDestroy() {
        PlanUpdateService.stopService(this);
        stopInAppBillingHelper();
        super.onDestroy();
    }

    private void updatePurchaseUI(int position) {
        Fragment fragment = getPageAdapter().getItem(position);
        if (!(fragment instanceof PlanFragment)) {
            return;
        }

        SitePlan sitePlan = ((PlanFragment) fragment).getSitePlan();
        Plan globalPlan = PlansUtils.getGlobalPlan(sitePlan.getProductID());
        if (globalPlan == null) {
            AppLog.w(AppLog.T.PLANS, "unable to match global plan " + sitePlan.getProductID());
            finish();
            return;
        }

        boolean showPurchaseButton;
        if (sitePlan.isCurrentPlan()) {
            showPurchaseButton = false;
        } else {
            // don't show the purchase button unless the plan at this position is "greater" than
            // the current plan for this site
            long currentPlanProductId = WordPress.wpDB.getPlanIdForLocalTableBlogId(mLocalBlogID);
            showPurchaseButton = (PlansUtils.compareProducts(sitePlan.getProductID(), currentPlanProductId) == PlansUtils.GREATER_PRODUCT);
        }

        ViewGroup framePurchase = (ViewGroup) findViewById(R.id.frame_purchase);
        ViewGroup containerPurchase = (ViewGroup) findViewById(R.id.purchase_container);
        if (showPurchaseButton) {
            TextView txtPurchasePrice = (TextView) framePurchase.findViewById(R.id.text_purchase_price);
            txtPurchasePrice.setText(PlansUtils.getPlanDisplayPrice(globalPlan));
            containerPurchase.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startPurchaseProcess();
                }
            });
        } else {
            containerPurchase.setOnClickListener(null);
        }

        if (showPurchaseButton && framePurchase.getVisibility() != View.VISIBLE) {
            AniUtils.animateBottomBar(framePurchase, true);
        } else if (!showPurchaseButton && framePurchase.getVisibility() == View.VISIBLE) {
            AniUtils.animateBottomBar(framePurchase, false);
        }
    }

    private void setupPlansUI() {
        if (mAvailablePlans == null || mAvailablePlans.length == 0)  {
            // This should never be called with empty plans.
            Toast.makeText(PlansActivity.this, R.string.plans_loading_error, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        hideProgress();

        mViewPager.setOffscreenPageLimit(mAvailablePlans.length - 1);
        mViewPager.setAdapter(getPageAdapter());

        mTabLayout.setTabMode(TabLayout.MODE_FIXED);
        int normalColor = getResources().getColor(R.color.blue_light);
        int selectedColor = getResources().getColor(R.color.white);
        mTabLayout.setTabTextColors(normalColor, selectedColor);
        mTabLayout.setupWithViewPager(mViewPager);

        // Move the viewpager on the blog plan if no prev position is available
        if (mViewpagerPosSelected == NO_PREV_POS_SELECTED_VIEWPAGER) {
            for (SitePlan currentSitePlan : mAvailablePlans) {
                if (currentSitePlan.isCurrentPlan()) {
                    mViewpagerPosSelected = getPageAdapter().getPositionOfPlan(currentSitePlan.getProductID());
                }
            }
        }
        if (getPageAdapter().isValidPosition(mViewpagerPosSelected)) {
            mViewPager.setCurrentItem(mViewpagerPosSelected);
        }

        if (mViewPager.getVisibility() != View.VISIBLE) {
            // use a circular reveal on API 21+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                revealViewPager();
            } else {
                mViewPager.setVisibility(View.VISIBLE);
                mTabLayout.setVisibility(View.VISIBLE);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void revealViewPager() {
        mViewPager.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                mViewPager.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                Point pt = DisplayUtils.getDisplayPixelSize(PlansActivity.this);
                float startRadius = 0f;
                float endRadius = (float) Math.hypot(pt.x, pt.y);
                int centerX = pt.x / 2;
                int centerY = pt.y / 2;

                Animator anim = ViewAnimationUtils.createCircularReveal(mViewPager, centerX, centerY, startRadius, endRadius);
                anim.setDuration(getResources().getInteger(android.R.integer.config_longAnimTime));
                anim.setInterpolator(new AccelerateInterpolator());

                mViewPager.setVisibility(View.VISIBLE);
                mTabLayout.setVisibility(View.VISIBLE);

                anim.start();
            }
        });
    }

    private void hideProgress() {
        final ProgressBar progress = (ProgressBar) findViewById(R.id.progress_loading_plans);
        progress.setVisibility(View.GONE);
    }

    private void showProgress() {
        final ProgressBar progress = (ProgressBar) findViewById(R.id.progress_loading_plans);
        progress.setVisibility(View.VISIBLE);
    }

    private class PlansPageAdapter extends FragmentPagerAdapter {
        private final List<Fragment> mFragments;

        PlansPageAdapter(FragmentManager fm, List<Fragment> fragments) {
            super(fm);
            mFragments = fragments;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            if (mFragments != null && isValidPosition(position)) {
                return ((PlanFragment)mFragments.get(position)).getTitle();
            }
            return super.getPageTitle(position);
        }

        @Override
        public Fragment getItem(int position) {
            return mFragments.get(position);
        }

        @Override
        public int getCount() {
            return mFragments.size();
        }

        public boolean isValidPosition(int position) {
            return (position >= 0 && position < getCount());
        }

        public int getPositionOfPlan(long planID) {
            for (int i = 0; i < getCount(); i++) {
                PlanFragment fragment = (PlanFragment) getItem(i);
                if (fragment.getSitePlan().getProductID() == planID) {
                    return  i;
                }
            }
            return -1;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        EventBus.getDefault().register(this);
        // Download plans if not already available
        if (mAvailablePlans == null) {
            showProgress();
            PlanUpdateService.startService(this, mLocalBlogID);
        } else {
            setupPlansUI();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(ARG_LOCAL_TABLE_BLOG_ID, mLocalBlogID);
        outState.putSerializable(ARG_LOCAL_AVAILABLE_PLANS, mAvailablePlans);
        if (mViewPager != null) {
            outState.putInt(SAVED_VIEWPAGER_POS, mViewPager.getCurrentItem());
            // trick to restore the correct pos of the view pager without using a listener when the activity is not restarted.
            mViewpagerPosSelected = mViewPager.getCurrentItem();
        }
        super.onSaveInstanceState(outState);
    }

    private PlansPageAdapter getPageAdapter() {
        if (mPageAdapter == null) {
            List<Fragment> fragments = new ArrayList<>();
            if (mAvailablePlans != null) {
                for (SitePlan plan : mAvailablePlans) {
                    fragments.add(PlanFragment.newInstance(plan));
                }
            }

            FragmentManager fm = getFragmentManager();
            mPageAdapter = new PlansPageAdapter(fm, fragments);
        }
        return mPageAdapter;
    }

    /*
     * called by the service when plan data is successfully updated
     */
    @SuppressWarnings("unused")
    public void onEventMainThread(PlanEvents.PlansUpdated event) {
        // make sure the update is for this blog
        if (event.getLocalBlogId() != this.mLocalBlogID) {
            AppLog.w(AppLog.T.PLANS, "plans updated for different blog");
            return;
        }

        List<SitePlan> plans = event.getPlans();
        mAvailablePlans = new SitePlan[plans.size()];
        plans.toArray(mAvailablePlans);

        // make sure plans are correctly sorted
        Arrays.sort(mAvailablePlans, new Comparator<SitePlan>() {
            @Override
            public int compare(SitePlan lhs, SitePlan rhs) {
                return PlansUtils.compareProducts(lhs.getProductID(), rhs.getProductID());
            }
        });

        setupPlansUI();
    }

    /*
     * called by the service when plan data fails to update
     */
    @SuppressWarnings("unused")
    public void onEventMainThread(PlanEvents.PlansUpdateFailed event) {
        Toast.makeText(PlansActivity.this, R.string.plans_loading_error, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void startPurchaseProcess() {
        // TODO: this should start the Google Play purchase process, for now it shows the
        // post-purchase on-boarding
        Intent intent = new Intent(this, PlanPostPurchaseActivity.class);
        startActivity(intent);
        finish();
    }

    /*
     * initialize the Google Play in-app billing helper - note that IAB requires a real device,
     * so this will always fail on an emulator
     * TODO: for now this is just skeleton code showing how it's done
     */
    private void startInAppBillingHelper() {
        mIabHelper = new IabHelper(this, BuildConfig.APP_LICENSE_KEY);
        if (BuildConfig.DEBUG) {
            String tag = AppLog.TAG + "-" + AppLog.T.PLANS.toString();
            mIabHelper.enableDebugLogging(true, tag);
        }
        try {
            mIabHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
                @Override
                public void onIabSetupFinished(IabResult result) {
                    if (result.isSuccess()) {
                        AppLog.d(AppLog.T.PLANS, "IAB started successfully");
                    } else {
                        AppLog.w(AppLog.T.PLANS, "IAB failed with " + result);
                    }
                }
            });
        } catch (NullPointerException e) {
            // will happen when play store isn't available on device
            AppLog.e(AppLog.T.PLANS, e);
            AppLog.w(AppLog.T.PLANS, "Unable to start IAB helper");
        }
    }

    private void stopInAppBillingHelper() {
        if (mIabHelper != null) {
            try {
                mIabHelper.dispose();
            } catch (IllegalArgumentException e) {
                // this can happen if the IAB helper was created but failed to bind to its service
                // when started, which will occur on emulators
                AppLog.e(AppLog.T.PLANS, e);
            }
            mIabHelper = null;
        }
    }

}