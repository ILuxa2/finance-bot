package financeos.data;

import java.net.URI;

public final class GoogleSheetsCopyProbeTest {
    public static void main(String[] args) {
        GoogleSheetsCopyProbe.requireTestCopy(GoogleSheetsCopyProbe.TEST_COPY_ID);
        boolean refusedOriginal = false;
        try {
            new GoogleSheetsCopyProbe(URI.create("https://sheets.googleapis.com"),
                    "EXAMPLE_ORIGINAL_SPREADSHEET_ID", () -> "token");
        } catch (IllegalArgumentException expected) {
            refusedOriginal = true;
        }
        if (!refusedOriginal) throw new AssertionError("Original spreadsheet was accepted for writing");
        System.out.println("Google Sheets copy-only guard checks passed.");
    }
}
