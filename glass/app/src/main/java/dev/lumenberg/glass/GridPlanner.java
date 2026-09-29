package dev.lumenberg.glass;

/** Pixel geometry shared by every built-in and hosted widget. */
public final class GridPlanner {
    private GridPlanner() {}
    public static int smallWidth(int available, int gap) {
        return Math.max(1, (available - Math.max(0, gap)) / 2);
    }
    public static int width(int available, int gap, int span) {
        return span == 1 ? smallWidth(available, gap) : Math.max(1, available);
    }
    public static int height(int width, boolean square, int preferred) {
        return square ? width : Math.max(1, preferred);
    }
}
