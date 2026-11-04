package com.frostbyte.freezer;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    enum Filter {
        ALL, USER, SYSTEM, FROZEN
    }

    interface Listener {
        void onAppClick(AppInfo app);

        void onDataChanged(int visibleCount);
    }

    private final Context context;
    private final Listener listener;
    private final LayoutInflater inflater;
    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> visibleApps = new ArrayList<>();
    private final int highlightColor;
    private final Drawable fallbackIcon;

    private Filter filter = Filter.ALL;
    private String query = "";

    AppListAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.inflater = LayoutInflater.from(context);
        this.highlightColor = ContextCompat.getColor(context, R.color.primary);
        this.fallbackIcon = ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon);
        setHasStableIds(true);
    }

    void setAll(List<AppInfo> apps) {
        allApps.clear();
        allApps.addAll(apps);
        rebuild();
    }

    void setFilter(Filter filter) {
        if (this.filter == filter) {
            return;
        }
        this.filter = filter;
        rebuild();
    }

    void setQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        if (this.query.equals(normalized)) {
            return;
        }
        this.query = normalized;
        rebuild();
    }

    void applyFrozen(Set<String> frozenPackages) {
        for (AppInfo app : allApps) {
            app.frozen = frozenPackages.contains(app.packageName);
        }
        rebuild();
    }

    List<AppInfo> getAllApps() {
        return allApps;
    }

    int getFrozenCount() {
        int count = 0;
        for (AppInfo app : allApps) {
            if (app.frozen) {
                count++;
            }
        }
        return count;
    }

    private void rebuild() {
        visibleApps.clear();
        for (AppInfo app : allApps) {
            if (!matchesFilter(app)) {
                continue;
            }
            if (!query.isEmpty()
                    && !containsIgnoreCase(app.label, query)
                    && !containsIgnoreCase(app.packageName, query)
                    && !matchesUid(app.uid, query)) {
                continue;
            }
            visibleApps.add(app);
        }
        notifyDataSetChanged();
        listener.onDataChanged(visibleApps.size());
    }

    private boolean matchesFilter(AppInfo app) {
        switch (filter) {
            case USER:
                return !app.system;
            case SYSTEM:
                return app.system;
            case FROZEN:
                return app.frozen;
            case ALL:
            default:
                return true;
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = inflater.inflate(R.layout.item_app, parent, false);
        final ViewHolder holder = new ViewHolder(view);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && position < visibleApps.size()) {
                    listener.onAppClick(visibleApps.get(position));
                }
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = visibleApps.get(position);
        holder.icon.setImageDrawable(app.icon != null ? app.icon : fallbackIcon);
        holder.name.setText(highlight(app.label, query));
        holder.packageName.setText(highlight(app.packageName, query));

        if (app.frozen) {
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setText(R.string.status_frozen);
            holder.status.setTextColor(ContextCompat.getColor(context, R.color.frozen_text));
            holder.status.setBackgroundResource(R.drawable.bg_pill_frozen);
        } else if (app.system) {
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setText(R.string.status_system);
            holder.status.setTextColor(ContextCompat.getColor(context, R.color.system_text));
            holder.status.setBackgroundResource(R.drawable.bg_pill_system);
        } else {
            holder.status.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return visibleApps.size();
    }

    @Override
    public long getItemId(int position) {
        return visibleApps.get(position).packageName.hashCode();
    }

    private CharSequence highlight(String text, String search) {
        if (search.isEmpty()) {
            return text;
        }
        SpannableString spannable = new SpannableString(text);
        int index = 0;
        while (index <= text.length() - search.length()) {
            if (text.regionMatches(true, index, search, 0, search.length())) {
                spannable.setSpan(new ForegroundColorSpan(highlightColor),
                        index, index + search.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                index += search.length();
            } else {
                index++;
            }
        }
        return spannable;
    }

    private static boolean matchesUid(int uid, String search) {
        if (search.isEmpty()) {
            return false;
        }
        for (int i = 0; i < search.length(); i++) {
            if (!Character.isDigit(search.charAt(i))) {
                return false;
            }
        }
        return String.valueOf(uid).equals(search);
    }

    private static boolean containsIgnoreCase(String text, String search) {
        if (search.isEmpty()) {
            return true;
        }
        int max = text.length() - search.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, search, 0, search.length())) {
                return true;
            }
        }
        return false;
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView status;

        ViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.app_icon);
            name = view.findViewById(R.id.app_name);
            packageName = view.findViewById(R.id.app_package);
            status = view.findViewById(R.id.app_status);
        }
    }
}
