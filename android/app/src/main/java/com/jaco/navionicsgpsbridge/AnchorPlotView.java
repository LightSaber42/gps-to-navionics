package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** North-up, automatically scaled plot of the recent anchor-watch GPS trail. */
final class AnchorPlotView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<BridgeState.AnchorTrackPoint> points = Collections.emptyList();
    private double limitMeters = 15.0;
    private boolean alarmTriggered;

    AnchorPlotView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Anchor drift plot. Waiting for GPS points.");
    }

    void setState(BridgeState state) {
        points = state.anchorTrackPoints;
        limitMeters = state.anchorRadiusMeters;
        alarmTriggered = state.anchorAlarmTriggered;
        String distance = Double.isFinite(state.anchorDistanceMeters)
                ? String.format(Locale.US, "Current drift %.1f metres. ", state.anchorDistanceMeters)
                : "Waiting for a current GPS fix. ";
        setContentDescription(distance + String.format(
                Locale.US,
                "Alarm limit %.0f metres. %d GPS points plotted.",
                limitMeters,
                points.size()));
        invalidate();
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

        double furthestPoint = 0.0;
        for (BridgeState.AnchorTrackPoint point : points) {
            furthestPoint = Math.max(
                    furthestPoint,
                    Math.hypot(point.eastMeters, point.northMeters) + point.accuracyMeters);
        }
        double extentMeters = Math.max(5.0,
                Math.max(limitMeters * 1.25, furthestPoint * 1.15));
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

        if (points.size() > 1) {
            Path trail = new Path();
            for (int index = 0; index < points.size(); index++) {
                BridgeState.AnchorTrackPoint point = points.get(index);
                float x = centerX + (float) point.eastMeters * pixelsPerMeter;
                float y = centerY - (float) point.northMeters * pixelsPerMeter;
                if (index == 0) trail.moveTo(x, y); else trail.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(withAlpha(color(R.color.nav_blue), 185));
            canvas.drawPath(trail, paint);
        }

        int pointStride = Math.max(1, points.size() / 160);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color(R.color.nav_blue), 145));
        for (int index = 0; index < points.size(); index += pointStride) {
            BridgeState.AnchorTrackPoint point = points.get(index);
            float x = centerX + (float) point.eastMeters * pixelsPerMeter;
            float y = centerY - (float) point.northMeters * pixelsPerMeter;
            canvas.drawCircle(x, y, dp(1.6f), paint);
        }

        if (!points.isEmpty()) {
            BridgeState.AnchorTrackPoint current = points.get(points.size() - 1);
            float currentX = centerX + (float) current.eastMeters * pixelsPerMeter;
            float currentY = centerY - (float) current.northMeters * pixelsPerMeter;
            float accuracyRadius = (float) current.accuracyMeters * pixelsPerMeter;
            if (accuracyRadius > 1.0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(color(R.color.nav_success), 22));
                canvas.drawCircle(currentX, currentY, accuracyRadius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(withAlpha(color(R.color.nav_success), 75));
                canvas.drawCircle(currentX, currentY, accuracyRadius, paint);
            }
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
        canvas.drawText(String.format(Locale.US, "%.0f m limit", limitMeters), centerX, labelY, paint);
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
