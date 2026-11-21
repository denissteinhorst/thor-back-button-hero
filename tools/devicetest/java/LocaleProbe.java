// SPDX-License-Identifier: 0BSD
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Reads Android's decimal separator for the harness without changing the device's locale. */
public final class LocaleProbe {
    public static void main(String[] args) {
        System.out.println(DecimalFormatSymbols.getInstance(Locale.forLanguageTag(args[0])).getDecimalSeparator());
    }
}
