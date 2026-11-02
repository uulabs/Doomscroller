package au.com.uulabs.doomscroller;

/** Display geometry already includes decoder-applied rotation. Zero means unknown. */
public final class VideoGeometry {
    public static double ratio(int width,int height,float pixelRatio) {
        if(width<=0 || height<=0 || !Float.isFinite(pixelRatio) || pixelRatio<=0)return 0;
        double r=(double)width*pixelRatio/height;
        return Double.isFinite(r) && r>0 ? r : 0;
    }
    public static boolean allowed(double ratio,boolean landscape) {
        return landscape || (Double.isFinite(ratio) && ratio>0 && ratio<=1);
    }
}
