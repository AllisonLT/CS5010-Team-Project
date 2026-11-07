package edu.northeastern.cs5010.desktop.ui;

/** Entries of the navigation list (the PDF's "Main Menu"). */
public enum Screen {
    HOME("Home"),
    DATASETS("Datasets"),
    FORECAST("Temperature Forecast"),
    SHELF_LIFE("Products & Shelf Life"),
    HISTORY("Prediction History"),
    AUDIT("Audit Log");

    private final String label;

    Screen(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
