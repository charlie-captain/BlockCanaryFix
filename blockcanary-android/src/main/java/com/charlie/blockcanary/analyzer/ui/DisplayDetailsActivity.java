/*
 * Copyright (C) 2015 Square, Inc.
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
package com.nf.blockcanary.analyzer.ui;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.nf.blockcanary.BlockCanaryContext;
import com.nf.blockcanary.BlockCanaryInternals;
import com.nf.blockcanary.LogWriter;
import com.nf.blockcanary.R;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

/**
 * Display blocks.
 */
public class DisplayDetailsActivity extends Activity {

  private static final String TAG = "DisplayActivity";
  private static final String SHOW_BLOCK_EXTRA = "show_latest";
  public static final String SHOW_BLOCK_EXTRA_KEY = "BlockStartTime";

  // empty until it's been first loaded.
  private List<BlockInfoEx> mBlockInfoEntries = new ArrayList<>();
  private String mBlockStartTime;

  private ListView mListView;
  private TextView mFailureView;
  private Button mActionButton;
  private int mMaxStoredBlockCount;

  public static PendingIntent createPendingIntent(Context context, String blockStartTime) {
    Intent intent = new Intent(context, DisplayDetailsActivity.class);
    intent.putExtra(SHOW_BLOCK_EXTRA, blockStartTime);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (savedInstanceState != null) {
      mBlockStartTime = savedInstanceState.getString(SHOW_BLOCK_EXTRA_KEY);
    } else {
      Intent intent = getIntent();
      if (intent.hasExtra(SHOW_BLOCK_EXTRA)) {
        mBlockStartTime = intent.getStringExtra(SHOW_BLOCK_EXTRA);
      }
    }

    setContentView(R.layout.block_canary_display_leak);

    mListView = (ListView) findViewById(R.id.__leak_canary_display_leak_list);
    mFailureView = (TextView) findViewById(R.id.__leak_canary_display_leak_failure);
    mActionButton = (Button) findViewById(R.id.__leak_canary_action);

    mMaxStoredBlockCount = getResources().getInteger(R.integer.block_canary_max_stored_count);
    LoadBlocks.load(this);

    updateUi();
  }

  // No, it's not deprecated. Android lies.
  @Override
  public Object onRetainNonConfigurationInstance() {
    return mBlockInfoEntries;
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(SHOW_BLOCK_EXTRA_KEY, mBlockStartTime);
  }

  @Override
  protected void onResume() {
    super.onResume();
  }

  @Override
  public void setTheme(int resid) {
    // We don't want this to be called with an incompatible theme.
    // This could happen if you implement runtime switching of themes
    // using ActivityLifecycleCallbacks.
    if (resid != R.style.block_canary_BlockCanary_Base) {
      return;
    }
    super.setTheme(resid);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    LoadBlocks.forgetActivity();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    final BlockInfoEx blockInfo = getBlock(mBlockStartTime);
    if (blockInfo != null) {
      menu.add(R.string.block_canary_share_leak)
          .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
              shareBlock(blockInfo);
              return true;
            }
          });
      menu.add(R.string.block_canary_share_stack_dump)
          .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
              shareHeapDump(blockInfo);
              return true;
            }
          });
      return true;
    }
    return false;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      onBackPressed();
    }
    return true;
  }

  @Override
  public void onBackPressed() {
    super.onBackPressed();
  }

  private void shareBlock(BlockInfoEx blockInfo) {
    String leakInfo = blockInfo.toString();
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, leakInfo);
    startActivity(Intent.createChooser(intent, getString(R.string.block_canary_share_with)));
  }

  private void shareHeapDump(BlockInfoEx blockInfo) {
    File heapDumpFile = blockInfo.logFile;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
      heapDumpFile.setReadable(true, false);
    }
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("application/octet-stream");
    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(heapDumpFile));
    startActivity(Intent.createChooser(intent, getString(R.string.block_canary_share_with)));
  }

  private void updateUi() {
    final BlockInfoEx blockInfo = getBlock(mBlockStartTime);
    //if (blockInfo == null) {
    //    mBlockStartTime = null;
    //}

    // Reset to defaults
    mListView.setVisibility(VISIBLE);
    mFailureView.setVisibility(GONE);

    if (blockInfo != null) {
      renderBlockDetail(blockInfo);
    }
  }

  private void renderBlockDetail(final BlockInfoEx blockInfo) {
    ListAdapter listAdapter = mListView.getAdapter();
    final DetailAdapter adapter;
    if (listAdapter instanceof DetailAdapter) {
      adapter = (DetailAdapter) listAdapter;
    } else {
      adapter = new DetailAdapter();
      mListView.setAdapter(adapter);
      mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
          adapter.toggleRow(position);
        }
      });
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
        invalidateOptionsMenu();
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
          actionBar.setDisplayHomeAsUpEnabled(true);
        }
      }
      mActionButton.setVisibility(VISIBLE);
      mActionButton.setText(R.string.block_canary_delete);
    }
    mActionButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (blockInfo != null) {
          Intent intent = new Intent();
          intent.putExtra("details_result", blockInfo.logFile.getName());
          blockInfo.logFile.delete();
          setResult(RESULT_OK, intent);
          finish();
        }
      }
    });
    adapter.update(blockInfo);
    setTitle(getString(R.string.block_canary_class_has_blocked, blockInfo.timeCost));
  }

  private BlockInfoEx getBlock(String startTime) {
    if (mBlockInfoEntries == null || TextUtils.isEmpty(startTime)) {
      return null;
    }
    for (BlockInfoEx blockInfo : mBlockInfoEntries) {
      if (blockInfo.timeStart != null && startTime.equals(blockInfo.timeStart)) {
        return blockInfo;
      }
    }
    return null;
  }

  static class LoadBlocks implements Runnable {

    static final List<LoadBlocks> inFlight = new ArrayList<>();
    static final Executor backgroundExecutor = Executors.newSingleThreadExecutor();
    private DisplayDetailsActivity activityOrNull;
    private final Handler mainHandler;

    LoadBlocks(DisplayDetailsActivity activity) {
      this.activityOrNull = activity;
      mainHandler = new Handler(Looper.getMainLooper());
    }

    static void load(DisplayDetailsActivity activity) {
      LoadBlocks loadBlocks = new LoadBlocks(activity);
      inFlight.add(loadBlocks);
      backgroundExecutor.execute(loadBlocks);
    }

    static void forgetActivity() {
      for (LoadBlocks loadBlocks : inFlight) {
        loadBlocks.activityOrNull = null;
      }
      inFlight.clear();
    }

    @Override
    public void run() {
      final List<BlockInfoEx> blockInfoList = new ArrayList<>();
      File[] files = BlockCanaryInternals.getLogFiles();
      if (files != null) {
        for (File blockFile : files) {
          try {
            BlockInfoEx blockInfo = BlockInfoEx.newInstance(blockFile);
            if (!BlockCanaryUtils.isBlockInfoValid(blockInfo)) {
              throw new BlockInfoCorruptException(blockInfo);
            }

            boolean needAddToList = true;

            if (BlockCanaryUtils.isInWhiteList(blockInfo)) {
              if (BlockCanaryContext.get().deleteFilesInWhiteList()) {
                blockFile.delete();
                blockFile = null;
              }
              needAddToList = false;
            }

            blockInfo.concernStackString = BlockCanaryUtils.concernStackString(blockInfo);
            if (BlockCanaryContext.get().filterNonConcernStack() &&
                TextUtils.isEmpty(blockInfo.concernStackString)) {
              needAddToList = false;
            }

            if (needAddToList && blockFile != null) {
              blockInfoList.add(blockInfo);
            }
          } catch (Exception e) {
            // Probably blockFile corrupts or format changes, just delete it.
            blockFile.delete();
            Log.e(TAG, "Could not read block log file, deleted :" + blockFile, e);
          }
        }
        Collections.sort(blockInfoList, new Comparator<BlockInfoEx>() {
          @Override
          public int compare(BlockInfoEx lhs, BlockInfoEx rhs) {
            //return Long.valueOf(rhs.logFile.lastModified())
            //        .compareTo(lhs.logFile.lastModified());

            return Long.valueOf(rhs.timeCost).compareTo(lhs.timeCost);
          }
        });
      }
      mainHandler.post(new Runnable() {
        @Override
        public void run() {
          inFlight.remove(LoadBlocks.this);
          if (activityOrNull != null) {
            activityOrNull.mBlockInfoEntries = blockInfoList;
            Log.d(TAG, "load block entries: " + blockInfoList.size());
            activityOrNull.updateUi();
          }
        }
      });
    }
  }
}