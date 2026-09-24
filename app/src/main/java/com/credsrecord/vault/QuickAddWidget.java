package com.credsrecord.vault;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** A privacy-preserving launcher shortcut: never reads or renders vault content. */
public final class QuickAddWidget extends AppWidgetProvider {
    public static final String ACTION_ADD_ENTRY = "com.credsrecord.vault.ADD_ENTRY";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int widgetId : widgetIds) {
            Intent launch = new Intent(context, MainActivity.class);
            launch.setAction(ACTION_ADD_ENTRY);
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntent.getActivity(context, widgetId, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.widget_quick_add);
            view.setOnClickPendingIntent(R.id.widget_root, open);
            view.setOnClickPendingIntent(R.id.widget_add_button, open);
            manager.updateAppWidget(widgetId, view);
        }
    }
}
