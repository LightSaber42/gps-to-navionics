package com.jaco.navionicsgpsbridge;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

final class AnchorQrCode {
    private AnchorQrCode() {
    }

    static Bitmap bitmap(String value, int sizePixels) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(
                value, BarcodeFormat.QR_CODE, sizePixels, sizePixels, hints);
        int[] pixels = new int[sizePixels * sizePixels];
        for (int y = 0; y < sizePixels; y++) {
            for (int x = 0; x < sizePixels; x++) {
                pixels[y * sizePixels + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(
                sizePixels, sizePixels, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, sizePixels, 0, 0, sizePixels, sizePixels);
        return bitmap;
    }
}
