package com.plstk.loyaltybot.service.commerce;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public final class ImageAlphaUtils {

    private static final int ALPHA_THRESHOLD = 10;

    private ImageAlphaUtils() {}

    public static Rectangle findNonTransparentBoundingBox(BufferedImage image) {
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        boolean found = false;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = (image.getRGB(x, y) >> 24) & 0xFF;
                if (alpha > ALPHA_THRESHOLD) {
                    found = true;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (!found || maxX < minX || maxY < minY) {
            return new Rectangle(0, 0, width, height);
        }
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public static BufferedImage crop(BufferedImage source, Rectangle bounds) {
        return source.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
    }
}
