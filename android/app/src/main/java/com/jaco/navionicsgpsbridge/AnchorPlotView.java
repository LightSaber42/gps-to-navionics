package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** North-up, automatically scaled plot of the recent anchor-watch GPS trail. */
final class AnchorPlotView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<BridgeState.AnchorTrackPoint> points = Collections.emptyList();
    private double limitMeters = 15.0;
    private boolean alarmTriggered;
    private DisplayUnits displayUnits;

    AnchorPlotView(Context context) {
        super(context);
        displayUnits = DisplayUnits.load(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Anchor drift plot. Waiting for GPS points.");
    }

    void setDisplayUnits(DisplayUnits displayUnits) {
        this.displayUnits = displayUnits;
    }

    void setState(BridgeState state) {
        setRemoteState(
                state.anchorTrackPoints,
                state.anchorRadiusMeters,
                state.anchorAlarmTriggered,
                state.anchorDistanceMeters);
    }

    void setRemoteState(List<BridgeState.AnchorTrackPoint> newPoints,
            double newLimitMeters, boolean newAlarmTriggered, double distanceMeters) {
        points = newPoints;
        limitMeters = newLimitMeters;
        alarmTriggered = newAlarmTriggered;
        String distance = Double.isFinite(distanceMeters)
                ? "Current drift " + displayUnits.format(distanceMeters, 1) + ". "
                : "Waiting for a current GPS fix. ";
        setContentDescription(distance + "Alarm limit " + displayUnits.format(limitMeters)
                + ". " + points.size() + " GPS points plotted.");
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int size = Math.min(availableWidth, Math.round(dp(420)));
        setMeasuredDimension(
                resolveSize(size, widthMeasureSpec),
                resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;
        float padding = dp(30);
        float drawableRadius = Math.max(1.0f, Math.min(width, height) / 2.0f - padding);

        int recentStart = Math.max(0, points.size() - 120);
        List<Double> recentExtents = new ArrayList<>();
        for (int index = recentStart; index < points.size(); index++) {
            BridgeState.AnchorTrackPoint point = points.get(index);
            recentExtents.add(Math.hypot(point.eastMeters, point.northMeters)
                    + Math.min(Math.max(0.0, point.accuracyMeters), limitMeters));
        }
        Collections.sort(recentExtents);
        double representativeExtent = recentExtents.isEmpty() ? 0.0 : recentExtents.get(
                (int) Math.floor((recentExtents.size() - 1) * 0.95));
        BridgeState.AnchorTrackPoint scalePoint = points.isEmpty()
                ? null : points.get(points.size() - 1);
        double currentExtent = scalePoint == null ? 0.0
                : Math.hypot(scalePoint.eastMeters, scalePoint.northMeters)
                        + Math.min(Math.max(0.0, scalePoint.accuracyMeters), limitMeters);
        double extentMeters = Math.max(5.0, Math.max(
                limitMeters * 1.2,
                Math.max(representativeExtent * 1.12, currentExtent * 1.12)));
        float pixelsPerMeter = (float) (drawableRadius / extentMeters);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(withAlpha(color(R.color.nav_secondary), 45));
        paint.setPathEffect(null);
        canvas.drawLine(padding / 2, centerY, width - padding / 2, centerY, paint);
        canvas.drawLine(centerX, padding / 2, centerX, height - padding / 2, paint);

        float limitRadius = (float) limitMeters * pixelsPerMeter;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color(R.color.nav_blue), 18));
        canvas.drawCircle(centerX, centerY, limitRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(color(alarmTriggered ? R.color.nav_error : R.color.nav_warning));
        paint.setPathEffect(new DashPathEffect(new float[]{dp(9), dp(6)}, 0));
        canvas.drawCircle(centerX, centerY, limitRadius, paint);
        paint.setPathEffect(null);

        BridgeState.AnchorTrackPoint current = points.isEmpty()
                ? null : points.get(points.size() - 1);
        float currentX = centerX;
        float currentY = centerY;
        if (current != null) {
            currentX = centerX + (float) current.eastMeters * pixelsPerMeter;
            currentY = centerY - (float) current.northMeters * pixelsPerMeter;
            float accuracyRadius = (float) current.accuracyMeters * pixelsPerMeter;
            if (accuracyRadius > 1.0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(color(R.color.nav_success), 18));
                canvas.drawCircle(currentX, currentY, accuracyRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(withAlpha(color(R.color.nav_success), 75));
                canvas.drawCircle(currentX, currentY, accuracyRadius, paint);
            }
        }

        if (points.size() > 1) {
            Path trail = new Path();
            boolean segmentStarted = false;
            for (int index = 0; index < points.size(); index++) {
                BridgeState.AnchorTrackPoint point = points.get(index);
                if (Math.hypot(point.eastMeters, point.northMeters) > extentMeters * 1.05) {
                    segmentStarted = false;
                    continue;
                }
                float x = centerX + (float) point.eastMeters * pixelsPerMeter;
                float y = centerY - (float) point.northMeters * pixelsPerMeter;
                if (segmentStarted) trail.lineTo(x, y); else trail.moveTo(x, y);
                segmentStarted = true;
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(7));
            paint.setColor(withAlpha(Color.WHITE, 235));
            canvas.drawPath(trail, paint);
            paint.setStrokeWidth(dp(4));
            paint.setColor(color(R.color.nav_blue_dark));
            canvas.drawPath(trail, paint);
        }

        int pointStride = Math.max(1, points.size() / 160);
        paint.setStyle(Paint.Style.FILL);
        for (int index = 0; index < points.size(); index += pointStride) {
            BridgeState.AnchorTrackPoint point = points.get(index);
            if (Math.hypot(point.eastMeters, point.northMeters) > extentMeters * 1.05) {
                continue;
            }
            float x = centerX + (float) point.eastMeters * pixelsPerMeter;
            float y = centerY - (float) point.northMeters * pixelsPerMeter;
            paint.setColor(withAlpha(Color.WHITE, 235));
            canvas.drawCircle(x, y, dp(3.8f), paint);
            paint.setColor(color(R.color.nav_blue_dark));
            canvas.drawCircle(x, y, dp(2.4f), paint);
        }

        if (current != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(alarmTriggered ? R.color.nav_error : R.color.nav_success));
            canvas.drawCircle(currentX, currentY, dp(7), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(currentX, currentY, dp(7), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color(R.color.nav_text));
        canvas.drawCircle(centerX, centerY, dp(5), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        canvas.drawLine(centerX - dp(10), centerY, centerX + dp(10), centerY, paint);
        canvas.drawLine(centerX, centerY - dp(10), centerX, centerY + dp(10), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(12));
        paint.setColor(color(R.color.nav_secondary));
        canvas.drawText("N", centerX, dp(16), paint);
        paint.setTextSize(dp(11));
        paint.setColor(color(alarmTriggered ? R.color.nav_error : R.color.nav_warning));
        float labelY = Math.max(dp(29), centerY - limitRadius - dp(5));
        canvas.drawText(displayUnits.format(limitMeters) + " limit", centerX, labelY, paint);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int color(int resource) {
        return getContext().getColor(resource);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
