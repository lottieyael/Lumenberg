import dev.lumenberg.glass.GridPlanner;

public final class GridPlannerTest {
    public static void main(String[] args) {
        // Odd pixel widths must never push two small squares onto separate rows.
        for (int width = 120; width <= 1800; width++) {
            for (int gap : new int[]{8, 12, 24, 36}) {
                int small = GridPlanner.smallWidth(width, gap);
                if (small * 2 + gap > width) throw new AssertionError("Overflow at " + width);
                if (GridPlanner.height(small, true, 300) != small) throw new AssertionError("Not square");
                if (GridPlanner.width(width, gap, 2) != width) throw new AssertionError("Large tile");
                if (GridPlanner.height(small, false, 300) != 300) throw new AssertionError("Wide tile");
            }
        }
        System.out.println("Grid geometry: 6,724 viewport/gap combinations passed.");
    }
}
