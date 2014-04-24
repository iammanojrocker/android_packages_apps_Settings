/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.dashboard;

import android.app.Fragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.Utils;
import com.android.settings.search.Index;

import java.util.HashMap;

public class SearchResultsSummary extends Fragment {

    private static final String LOG_TAG = "SearchResultsSummary";

    private static final String EMPTY_QUERY = "";
    private static char ELLIPSIS = '\u2026';

    private SearchView mSearchView;

    private ListView mResultsListView;
    private SearchResultsAdapter mResultsAdapter;
    private UpdateSearchResultsTask mUpdateSearchResultsTask;

    private ListView mSuggestionsListView;
    private SuggestionsAdapter mSuggestionsAdapter;
    private UpdateSuggestionsTask mUpdateSuggestionsTask;

    private ViewGroup mLayoutSuggestions;
    private ViewGroup mLayoutResults;

    private String mQuery;

    /**
     * A basic AsyncTask for updating the query results cursor
     */
    private class UpdateSearchResultsTask extends AsyncTask<String, Void, Cursor> {
        @Override
        protected Cursor doInBackground(String... params) {
            return Index.getInstance(getActivity()).search(params[0]);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (!isCancelled()) {
                setResultsCursor(cursor);
            } else if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * A basic AsyncTask for updating the suggestions cursor
     */
    private class UpdateSuggestionsTask extends AsyncTask<String, Void, Cursor> {
        @Override
        protected Cursor doInBackground(String... params) {
            return Index.getInstance(getActivity()).getSuggestions(params[0]);
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            if (!isCancelled()) {
                setSuggestionsCursor(cursor);
            } else if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mResultsAdapter = new SearchResultsAdapter(getActivity());
        mSuggestionsAdapter = new SuggestionsAdapter(getActivity());
    }

    @Override
    public void onStop() {
        super.onStop();

        clearSuggestions();
        clearResults();
    }

    @Override
    public void onDestroy() {
        mResultsListView = null;
        mResultsAdapter = null;
        mUpdateSearchResultsTask = null;

        mSuggestionsListView = null;
        mSuggestionsAdapter = null;
        mUpdateSuggestionsTask = null;

        mSearchView = null;

        super.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        final View view = inflater.inflate(R.layout.search_panel, container, false);

        mLayoutSuggestions = (ViewGroup) view.findViewById(R.id.layout_suggestions);
        mLayoutResults = (ViewGroup) view.findViewById(R.id.layout_results);

        mResultsListView = (ListView) view.findViewById(R.id.list_results);
        mResultsListView.setAdapter(mResultsAdapter);
        mResultsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Cursor cursor = mResultsAdapter.mCursor;
                cursor.moveToPosition(position);

                final String className = cursor.getString(Index.COLUMN_INDEX_CLASS_NAME);
                final String screenTitle = cursor.getString(Index.COLUMN_INDEX_SCREEN_TITLE);
                final String action = cursor.getString(Index.COLUMN_INDEX_INTENT_ACTION);
                final String key = cursor.getString(Index.COLUMN_INDEX_KEY);

                final SettingsActivity sa = (SettingsActivity) getActivity();
                sa.needToRevertToInitialFragment();

                if (TextUtils.isEmpty(action)) {
                    Bundle args = new Bundle();
                    args.putString(SettingsActivity.EXTRA_FRAGMENT_ARG_KEY, key);

                    Utils.startWithFragment(sa, className, args, null, 0, screenTitle);
                } else {
                    final Intent intent = new Intent(action);

                    final String targetPackage = cursor.getString(
                            Index.COLUMN_INDEX_INTENT_ACTION_TARGET_PACKAGE);
                    final String targetClass = cursor.getString(
                            Index.COLUMN_INDEX_INTENT_ACTION_TARGET_CLASS);
                    if (!TextUtils.isEmpty(targetPackage) && !TextUtils.isEmpty(targetClass)) {
                        final ComponentName component =
                                new ComponentName(targetPackage, targetClass);
                        intent.setComponent(component);
                    }
                    intent.putExtra(SettingsActivity.EXTRA_FRAGMENT_ARG_KEY, key);

                    sa.startActivity(intent);
                }

                saveQueryToDatabase();
            }
        });

        mSuggestionsListView = (ListView) view.findViewById(R.id.list_suggestions);
        mSuggestionsListView.setAdapter(mSuggestionsAdapter);
        mSuggestionsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Cursor cursor = mSuggestionsAdapter.mCursor;
                cursor.moveToPosition(position);

                mQuery = cursor.getString(0);
                mSearchView.setQuery(mQuery, false);
                setSuggestionsVisibility(false);
            }
        });

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        showSomeSuggestions();
    }

    public void setSearchView(SearchView searchView) {
        mSearchView = searchView;
    }

    private void setSuggestionsVisibility(boolean visible) {
        if (mLayoutSuggestions != null) {
            mLayoutSuggestions.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setResultsVisibility(boolean visible) {
        if (mLayoutResults != null) {
            mLayoutResults.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void saveQueryToDatabase() {
        Index.getInstance(getActivity()).addSavedQuery(mQuery);
    }

    public boolean onQueryTextSubmit(String query) {
        mQuery = getFilteredQueryString(query);
        updateSearchResults();
        return true;
    }

    public boolean onQueryTextChange(String query) {
        mQuery = getFilteredQueryString(query);
        updateSuggestions();
        updateSearchResults();
        return true;
    }

    public void showSomeSuggestions() {
        setResultsVisibility(false);
        mQuery = EMPTY_QUERY;
        updateSuggestions();
    }

    private void clearSuggestions() {
        if (mUpdateSuggestionsTask != null) {
            mUpdateSuggestionsTask.cancel(false);
            mUpdateSuggestionsTask = null;
        }
        setSuggestionsCursor(null);
    }

    private void setSuggestionsCursor(Cursor cursor) {
        if (mSuggestionsAdapter == null) {
            return;
        }
        Cursor oldCursor = mSuggestionsAdapter.swapCursor(cursor);
        if (oldCursor != null) {
            oldCursor.close();
        }
    }

    private void clearResults() {
        if (mUpdateSearchResultsTask != null) {
            mUpdateSearchResultsTask.cancel(false);
            mUpdateSearchResultsTask = null;
        }
        setResultsCursor(null);
    }

    private void setResultsCursor(Cursor cursor) {
        if (mResultsAdapter == null) {
            return;
        }
        Cursor oldCursor = mResultsAdapter.swapCursor(cursor);
        if (oldCursor != null) {
            oldCursor.close();
        }
    }

    private String getFilteredQueryString(CharSequence query) {
        if (query == null) {
            return null;
        }
        final StringBuilder filtered = new StringBuilder();
        for (int n = 0; n < query.length(); n++) {
            char c = query.charAt(n);
            if (!Character.isLetterOrDigit(c) && !Character.isSpaceChar(c)) {
                continue;
            }
            filtered.append(c);
        }
        return filtered.toString();
    }

    private void updateSuggestions() {
        if (mUpdateSuggestionsTask != null) {
            mUpdateSuggestionsTask.cancel(false);
            mUpdateSuggestionsTask = null;
        }
        if (mQuery == null) {
            setSuggestionsCursor(null);
        } else {
            setSuggestionsVisibility(true);
            mUpdateSuggestionsTask = new UpdateSuggestionsTask();
            mUpdateSuggestionsTask.execute(mQuery);
        }
    }

    private void updateSearchResults() {
        if (mUpdateSearchResultsTask != null) {
            mUpdateSearchResultsTask.cancel(false);
            mUpdateSearchResultsTask = null;
        }
        if (TextUtils.isEmpty(mQuery)) {
            setResultsVisibility(false);
            setResultsCursor(null);
        } else {
            setResultsVisibility(true);
            mUpdateSearchResultsTask = new UpdateSearchResultsTask();
            mUpdateSearchResultsTask.execute(mQuery);
        }
    }

    private static class SuggestionItem {
        public String query;

        public SuggestionItem(String query) {
            this.query = query;
        }
    }

    private static class SuggestionsAdapter extends BaseAdapter {

        private static final int COLUMN_SUGGESTION_QUERY = 0;
        private static final int COLUMN_SUGGESTION_TIMESTAMP = 1;

        private Context mContext;
        private Cursor mCursor;
        private LayoutInflater mInflater;
        private boolean mDataValid = false;

        public SuggestionsAdapter(Context context) {
            mContext = context;
            mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mDataValid = false;
        }

        public Cursor swapCursor(Cursor newCursor) {
            if (newCursor == mCursor) {
                return null;
            }
            Cursor oldCursor = mCursor;
            mCursor = newCursor;
            if (newCursor != null) {
                mDataValid = true;
                notifyDataSetChanged();
            } else {
                mDataValid = false;
                notifyDataSetInvalidated();
            }
            return oldCursor;
        }

        @Override
        public int getCount() {
            if (!mDataValid || mCursor == null || mCursor.isClosed()) return 0;
            return mCursor.getCount();
        }

        @Override
        public Object getItem(int position) {
            if (mDataValid && mCursor.moveToPosition(position)) {
                final String query = mCursor.getString(COLUMN_SUGGESTION_QUERY);

                return new SuggestionItem(query);
            }
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (!mDataValid && convertView == null) {
                throw new IllegalStateException(
                        "this should only be called when the cursor is valid");
            }
            if (!mCursor.moveToPosition(position)) {
                throw new IllegalStateException("couldn't move cursor to position " + position);
            }

            View view;

            if (convertView == null) {
                view = mInflater.inflate(R.layout.search_suggestion_item, parent, false);
            } else {
                view = convertView;
            }

            TextView query = (TextView) view.findViewById(R.id.title);

            SuggestionItem item = (SuggestionItem) getItem(position);
            query.setText(item.query);

            return view;
        }
    }

    private static class SearchResult {
        public Context context;
        public String title;
        public String summaryOn;
        public String summaryOff;
        public String entries;
        public int iconResId;
        public String key;

        public SearchResult(Context context, String title, String summaryOn, String summaryOff,
                            String entries, int iconResId, String key) {
            this.context = context;
            this.title = title;
            this.summaryOn = summaryOn;
            this.summaryOff = summaryOff;
            this.entries = entries;
            this.iconResId = iconResId;
            this.key = key;
        }
    }

    private static class SearchResultsAdapter extends BaseAdapter {

        private Context mContext;
        private Cursor mCursor;
        private LayoutInflater mInflater;
        private boolean mDataValid;
        private HashMap<String, Context> mContextMap = new HashMap<String, Context>();

        private static final String PERCENT_RECLACE = "%s";
        private static final String DOLLAR_REPLACE = "$s";

        public SearchResultsAdapter(Context context) {
            mContext = context;
            mInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mDataValid = false;
        }

        public Cursor swapCursor(Cursor newCursor) {
            if (newCursor == mCursor) {
                return null;
            }
            Cursor oldCursor = mCursor;
            mCursor = newCursor;
            if (newCursor != null) {
                mDataValid = true;
                notifyDataSetChanged();
            } else {
                mDataValid = false;
                notifyDataSetInvalidated();
            }
            return oldCursor;
        }

        @Override
        public int getCount() {
            if (!mDataValid || mCursor == null || mCursor.isClosed()) return 0;
            return mCursor.getCount();
        }

        @Override
        public Object getItem(int position) {
            if (mDataValid && mCursor.moveToPosition(position)) {
                final String title = mCursor.getString(Index.COLUMN_INDEX_TITLE);
                final String summaryOn = mCursor.getString(Index.COLUMN_INDEX_SUMMARY_ON);
                final String summaryOff = mCursor.getString(Index.COLUMN_INDEX_SUMMARY_OFF);
                final String entries = mCursor.getString(Index.COLUMN_INDEX_ENTRIES);
                final String iconResStr = mCursor.getString(Index.COLUMN_INDEX_ICON);
                final String className = mCursor.getString(
                        Index.COLUMN_INDEX_CLASS_NAME);
                final String packageName = mCursor.getString(
                        Index.COLUMN_INDEX_INTENT_ACTION_TARGET_PACKAGE);
                final String key = mCursor.getString(
                        Index.COLUMN_INDEX_KEY);

                Context packageContext;
                if (TextUtils.isEmpty(className) && !TextUtils.isEmpty(packageName)) {
                    packageContext = mContextMap.get(packageName);
                    if (packageContext == null) {
                        try {
                            packageContext = mContext.createPackageContext(packageName, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(LOG_TAG, "Cannot create Context for package: " + packageName);
                            return null;
                        }
                        mContextMap.put(packageName, packageContext);
                    }
                } else {
                    packageContext = mContext;
                }

                final int iconResId = TextUtils.isEmpty(iconResStr) ?
                        R.drawable.empty_icon : Integer.parseInt(iconResStr);

                return new SearchResult(packageContext, title, summaryOn, summaryOff,
                        entries, iconResId, key);
            }
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (!mDataValid && convertView == null) {
                throw new IllegalStateException(
                        "this should only be called when the cursor is valid");
            }
            if (!mCursor.moveToPosition(position)) {
                throw new IllegalStateException("couldn't move cursor to position " + position);
            }

            View view;
            TextView textTitle;
            TextView textSummary;
            ImageView imageView;

            if (convertView == null) {
                view = mInflater.inflate(R.layout.search_result_item, parent, false);
            } else {
                view = convertView;
            }
            textTitle = (TextView) view.findViewById(R.id.title);
            textSummary = (TextView) view.findViewById(R.id.summary);
            imageView = (ImageView) view.findViewById(R.id.icon);

            SearchResult result = (SearchResult) getItem(position);

            textTitle.setText(result.title);

            String summaryOn = result.summaryOn;
            String entries = result.entries;

            final StringBuilder sb = new StringBuilder();

            if (!TextUtils.isEmpty(summaryOn) &&
                    !summaryOn.contains(PERCENT_RECLACE) && !summaryOn.contains(DOLLAR_REPLACE)) {
                sb.append(summaryOn);
                sb.append(ELLIPSIS);
            } else if (!TextUtils.isEmpty(entries)) {
                final int index  = entries.indexOf(Index.ENTRIES_SEPARATOR);
                if (index > 0) {
                    final String firstEntriesValue = entries.substring(0, index);
                    sb.append(firstEntriesValue);
                } else {
                    sb.append(entries);
                }
                sb.append(ELLIPSIS);
            }
            textSummary.setText(sb.toString());

            if (result.iconResId != R.drawable.empty_icon) {
                final Context packageContext = result.context;
                final Drawable drawable;
                try {
                    drawable = packageContext.getDrawable(result.iconResId);
                    imageView.setImageDrawable(drawable);
                } catch (Resources.NotFoundException nfe) {
                    // Not much we can do except logging
                    Log.e(LOG_TAG, "Cannot load Drawable for " + result.title);
                }
                imageView.setBackgroundResource(R.color.temporary_background_icon);
            } else {
                imageView.setImageDrawable(null);
                imageView.setBackgroundResource(R.drawable.empty_icon);
            }

            return view;
        }
    }
}
