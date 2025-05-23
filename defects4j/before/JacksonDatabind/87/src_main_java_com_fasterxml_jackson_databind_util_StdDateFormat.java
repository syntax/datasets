package com.fasterxml.jackson.databind.util;

import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

import com.fasterxml.jackson.core.io.NumberInput;

/**
 * Default {@link DateFormat} implementation used by standard Date
 * serializers and deserializers. For serialization defaults to using
 * an ISO-8601 compliant format (format String "yyyy-MM-dd'T'HH:mm:ss.SSSZ")
 * and for deserialization, both ISO-8601 and RFC-1123.
 */
@SuppressWarnings("serial")
public class StdDateFormat
    extends DateFormat
{
    /* TODO !!! 24-Nov-2009, tatu: Should rewrite this class:
     * JDK date parsing is awfully brittle, and ISO-8601 is quite
     * permissive. The two don't mix, need to write a better one.
     */
    // 02-Oct-2014, tatu: Alas. While spit'n'polished a few times, still
    //   not really robust. But still in use.

    /**
     * Defines a commonly used date format that conforms
     * to ISO-8601 date formatting standard, when it includes basic undecorated
     * timezone definition
     */
    public final static String DATE_FORMAT_STR_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    /**
     * Same as 'regular' 8601, but handles 'Z' as an alias for "+0000"
     * (or "UTC")
     */
    protected final static String DATE_FORMAT_STR_ISO8601_Z = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    /**
     * Same as 'regular' 8601 except misses timezone altogether
     *
     * @since 2.8.10
     */

    /**
     * ISO-8601 with just the Date part, no time
     */
    protected final static String DATE_FORMAT_STR_PLAIN = "yyyy-MM-dd";

    /**
     * This constant defines the date format specified by
     * RFC 1123 / RFC 822.
     */
    protected final static String DATE_FORMAT_STR_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /**
     * For error messages we'll also need a list of all formats.
     */
    protected final static String[] ALL_FORMATS = new String[] {
        DATE_FORMAT_STR_ISO8601,
        DATE_FORMAT_STR_ISO8601_Z,
        DATE_FORMAT_STR_RFC1123,
        DATE_FORMAT_STR_PLAIN
    };

    /**
     * By default we use UTC for everything, with Jackson 2.7 and later
     * (2.6 and earlier relied on GMT)
     */
    private final static TimeZone DEFAULT_TIMEZONE;
    static {
        DEFAULT_TIMEZONE = TimeZone.getTimeZone("UTC"); // since 2.7
    }

    private final static Locale DEFAULT_LOCALE = Locale.US;

    protected final static DateFormat DATE_FORMAT_RFC1123;

    protected final static DateFormat DATE_FORMAT_ISO8601;
    protected final static DateFormat DATE_FORMAT_ISO8601_Z;

    protected final static DateFormat DATE_FORMAT_PLAIN;

    /* Let's construct "blueprint" date format instances: can not be used
     * as is, due to thread-safety issues, but can be used for constructing
     * actual instances more cheaply (avoids re-parsing).
     */
    static {
        /* Another important thing: let's force use of default timezone for
         * baseline DataFormat objects
         */

        DATE_FORMAT_RFC1123 = new SimpleDateFormat(DATE_FORMAT_STR_RFC1123, DEFAULT_LOCALE);
        DATE_FORMAT_RFC1123.setTimeZone(DEFAULT_TIMEZONE);
        DATE_FORMAT_ISO8601 = new SimpleDateFormat(DATE_FORMAT_STR_ISO8601, DEFAULT_LOCALE);
        DATE_FORMAT_ISO8601.setTimeZone(DEFAULT_TIMEZONE);
        DATE_FORMAT_ISO8601_Z = new SimpleDateFormat(DATE_FORMAT_STR_ISO8601_Z, DEFAULT_LOCALE);
        DATE_FORMAT_ISO8601_Z.setTimeZone(DEFAULT_TIMEZONE);
        DATE_FORMAT_PLAIN = new SimpleDateFormat(DATE_FORMAT_STR_PLAIN, DEFAULT_LOCALE);
        DATE_FORMAT_PLAIN.setTimeZone(DEFAULT_TIMEZONE);
    }
    
    /**
     * A singleton instance can be used for cloning purposes, as a blueprint of sorts.
     */
    public final static StdDateFormat instance = new StdDateFormat();
    
    /**
     * Caller may want to explicitly override timezone to use; if so,
     * we will have non-null value here.
     */
    protected transient TimeZone _timezone;

    protected final Locale _locale;

    /**
     * Explicit override for leniency, if specified.
     *<p>
     * Can not be `final` because {@link #setLenient(boolean)} returns
     * `void`.
     *
     * @since 2.7
     */
    protected Boolean _lenient;
    
    protected transient DateFormat _formatRFC1123;
    protected transient DateFormat _formatISO8601;
    protected transient DateFormat _formatISO8601_z;
    protected transient DateFormat _formatPlain;

    /*
    /**********************************************************
    /* Life cycle, accessing singleton "standard" formats
    /**********************************************************
     */

    public StdDateFormat() {
        _locale = DEFAULT_LOCALE;
    }

    @Deprecated // since 2.7
    public StdDateFormat(TimeZone tz, Locale loc) {
        _timezone = tz;
        _locale = loc;
    }

    protected StdDateFormat(TimeZone tz, Locale loc, Boolean lenient) {
        _timezone = tz;
        _locale = loc;
        _lenient = lenient;
    }
    
    public static TimeZone getDefaultTimeZone() {
        return DEFAULT_TIMEZONE;
    }
    
    /**
     * Method used for creating a new instance with specified timezone;
     * if no timezone specified, defaults to the default timezone (UTC).
     */
    public StdDateFormat withTimeZone(TimeZone tz) {
        if (tz == null) {
            tz = DEFAULT_TIMEZONE;
        }
        if ((tz == _timezone) || tz.equals(_timezone)) {
            return this;
        }
        return new StdDateFormat(tz, _locale, _lenient);
    }

    public StdDateFormat withLocale(Locale loc) {
        if (loc.equals(_locale)) {
            return this;
        }
        return new StdDateFormat(_timezone, loc, _lenient);
    }
    
    @Override
    public StdDateFormat clone() {
        /* Although there is that much state to share, we do need to
         * orchestrate a bit, mostly since timezones may be changed
         */
        return new StdDateFormat(_timezone, _locale, _lenient);
    }

    /**
     * @deprecated Since 2.4; use variant that takes Locale
     */
    @Deprecated
    public static DateFormat getISO8601Format(TimeZone tz) {
        return getISO8601Format(tz, DEFAULT_LOCALE);
    }

    /**
     * Method for getting a non-shared DateFormat instance
     * that uses specified timezone and can handle simple ISO-8601
     * compliant date format.
     * 
     * @since 2.4
     */
    public static DateFormat getISO8601Format(TimeZone tz, Locale loc) {
        return _cloneFormat(DATE_FORMAT_ISO8601, DATE_FORMAT_STR_ISO8601, tz, loc, null);
    }

    /**
     * Method for getting a non-shared DateFormat instance
     * that uses specific timezone and can handle RFC-1123
     * compliant date format.
     * 
     * @since 2.4
     */
    public static DateFormat getRFC1123Format(TimeZone tz, Locale loc) {
        return _cloneFormat(DATE_FORMAT_RFC1123, DATE_FORMAT_STR_RFC1123,
                tz, loc, null);
    }

    /**
     * @deprecated Since 2.4; use variant that takes Locale
     */
    @Deprecated
    public static DateFormat getRFC1123Format(TimeZone tz) {
        return getRFC1123Format(tz, DEFAULT_LOCALE);
    }

    /*
    /**********************************************************
    /* Public API, configuration
    /**********************************************************
     */

    @Override // since 2.6
    public TimeZone getTimeZone() {
        return _timezone;
    }

    @Override
    public void setTimeZone(TimeZone tz)
    {
        /* DateFormats are timezone-specific (via Calendar contained),
         * so need to reset instances if timezone changes:
         */
        if (!tz.equals(_timezone)) {
            _clearFormats();
            _timezone = tz;
        }
    }

    /**
     * Need to override since we need to keep track of leniency locally,
     * and not via underlying {@link Calendar} instance like base class
     * does.
     */
    @Override // since 2.7
    public void setLenient(boolean enabled) {
        Boolean newValue = enabled;
        if (_lenient != newValue) {
            _lenient = newValue;
            // and since leniency strategySettings may have been used:
            _clearFormats();
        }
    }

    @Override // since 2.7
    public boolean isLenient() {
        if (_lenient == null) {
            // default is, I believe, true
            return true;
        }
        return _lenient.booleanValue();
    }

    /*
    /**********************************************************
    /* Public API, parsing
    /**********************************************************
     */

    @Override
    public Date parse(String dateStr) throws ParseException
    {
        dateStr = dateStr.trim();
        ParsePosition pos = new ParsePosition(0);

        Date dt;

        if (looksLikeISO8601(dateStr)) { // also includes "plain"
            dt = parseAsISO8601(dateStr, pos, true);
        } else {
            // Also consider "stringified" simple time stamp
            int i = dateStr.length();
            while (--i >= 0) {
                char ch = dateStr.charAt(i);
                if (ch < '0' || ch > '9') {
                    // 07-Aug-2013, tatu: And [databind#267] points out that negative numbers should also work
                    if (i > 0 || ch != '-') {
                        break;
                    }
                }
            }
            if ((i < 0)
                // let's just assume negative numbers are fine (can't be RFC-1123 anyway); check length for positive
                    && (dateStr.charAt(0) == '-' || NumberInput.inLongRange(dateStr, false))) {
                dt = new Date(Long.parseLong(dateStr));
            } else {
                // Otherwise, fall back to using RFC 1123
                dt = parseAsRFC1123(dateStr, pos);
            }
        }
        if (dt != null) {
            return dt;
        }

        StringBuilder sb = new StringBuilder();
        for (String f : ALL_FORMATS) {
            if (sb.length() > 0) {
                sb.append("\", \"");
            } else {
                sb.append('"');
            }
            sb.append(f);
        }
        sb.append('"');
        throw new ParseException
            (String.format("Can not parse date \"%s\": not compatible with any of standard forms (%s)",
                           dateStr, sb.toString()), pos.getErrorIndex());
    }

    @Override
    public Date parse(String dateStr, ParsePosition pos)
    {
        if (looksLikeISO8601(dateStr)) { // also includes "plain"
            try {
                return parseAsISO8601(dateStr, pos, false);
            } catch (ParseException e) { // will NOT be thrown due to false but is declared...
                return null;
            }
        }
        // Also consider "stringified" simple time stamp
        int i = dateStr.length();
        while (--i >= 0) {
            char ch = dateStr.charAt(i);
            if (ch < '0' || ch > '9') {
                // 07-Aug-2013, tatu: And [databind#267] points out that negative numbers should also work
                if (i > 0 || ch != '-') {
                    break;
                }
            }
        }
        if (i < 0) { // all digits
            // let's just assume negative numbers are fine (can't be RFC-1123 anyway); check length for positive
            if (dateStr.charAt(0) == '-' || NumberInput.inLongRange(dateStr, false)) {
                return new Date(Long.parseLong(dateStr));
            }
        }
        // Otherwise, fall back to using RFC 1123
        return parseAsRFC1123(dateStr, pos);
    }

    /*
    /**********************************************************
    /* Public API, writing
    /**********************************************************
     */
    
    @Override
    public StringBuffer format(Date date, StringBuffer toAppendTo,
            FieldPosition fieldPosition)
    {
        if (_formatISO8601 == null) {
            _formatISO8601 = _cloneFormat(DATE_FORMAT_ISO8601, DATE_FORMAT_STR_ISO8601,
                    _timezone, _locale, _lenient);
        }
        return _formatISO8601.format(date, toAppendTo, fieldPosition);
    }

    /*
    /**********************************************************
    /* Std overrides
    /**********************************************************
     */
    
    @Override
    public String toString() {
        String str = "DateFormat "+getClass().getName();
        TimeZone tz = _timezone;
        if (tz != null) {
            str += " (timezone: "+tz+")";
        }
        str += "(locale: "+_locale+")";
        return str;
    }

    @Override // since 2.7[.2], as per [databind#1130]
    public boolean equals(Object o) {
        return (o == this);
    }

    @Override // since 2.7[.2], as per [databind#1130]
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /*
    /**********************************************************
    /* Helper methods
    /**********************************************************
     */

    /**
     * Overridable helper method used to figure out which of supported
     * formats is the likeliest match.
     */
    protected boolean looksLikeISO8601(String dateStr)
    {
        if (dateStr.length() >= 5
            && Character.isDigit(dateStr.charAt(0))
            && Character.isDigit(dateStr.charAt(3))
            && dateStr.charAt(4) == '-'
            ) {
            return true;
        }
        return false;
    }

    protected Date parseAsISO8601(String dateStr, ParsePosition pos, boolean throwErrors)
            throws ParseException
    {
        /* 21-May-2009, tatu: DateFormat has very strict handling of
         * timezone  modifiers for ISO-8601. So we need to do some scrubbing.
         */

        /* First: do we have "zulu" format ('Z' == "UTC")? If yes, that's
         * quite simple because we already set date format timezone to be
         * UTC, and hence can just strip out 'Z' altogether
         */
        int len = dateStr.length();
        char c = dateStr.charAt(len-1);
        DateFormat df;
        String formatStr;

        // Need to support "plain" date...
        if (len <= 10 && Character.isDigit(c)) {
            df = _formatPlain;
            formatStr = DATE_FORMAT_STR_PLAIN;
            if (df == null) {
                df = _formatPlain = _cloneFormat(DATE_FORMAT_PLAIN, formatStr,
                        _timezone, _locale, _lenient);
            }
        } else if (c == 'Z') {
            df = _formatISO8601_z;
            formatStr = DATE_FORMAT_STR_ISO8601_Z;
            if (df == null) {
                // 10-Jun-2017, tatu: As per [databind#1651], when using this format,
                //    must use UTC, not whatever is configured as default timezone
                //    (because we know `Z` identifier is used)
                df = _formatISO8601_z = _cloneFormat(DATE_FORMAT_ISO8601_Z, formatStr,
                        DEFAULT_TIMEZONE, _locale, _lenient);
            }
            // may be missing milliseconds... if so, add
            if (dateStr.charAt(len-4) == ':') {
                StringBuilder sb = new StringBuilder(dateStr);
                sb.insert(len-1, ".000");
                dateStr = sb.toString();
            }
        } else {
            // Let's see if we have timezone indicator or not...
            if (hasTimeZone(dateStr)) {
                c = dateStr.charAt(len-3);
                if (c == ':') { // remove optional colon
                    // remove colon
                    StringBuilder sb = new StringBuilder(dateStr);
                    sb.delete(len-3, len-2);
                    dateStr = sb.toString();
                } else if (c == '+' || c == '-') { // missing minutes
                    // let's just append '00'
                    dateStr += "00";
                }
                // Milliseconds partial or missing; and even seconds are optional
                len = dateStr.length();
                // remove 'T', '+'/'-' and 4-digit timezone-offset
                int timeLen = len - dateStr.lastIndexOf('T') - 6;
                if (timeLen < 12) { // 8 for hh:mm:ss, 4 for .sss
                    int offset = len - 5; // insertion offset, before tz-offset
                    StringBuilder sb = new StringBuilder(dateStr);
                    switch (timeLen) {
                    case 11:
                        sb.insert(offset, '0'); break;
                    case 10:
                        sb.insert(offset, "00"); break;
                    case 9: // is this legal? (just second fraction marker)
                        sb.insert(offset, "000"); break;
                    case 8:
                        sb.insert(offset, ".000"); break;
                    case 7: // not legal to have single-digit second
                        break;
                    case 6: // probably not legal, but let's allow
                        sb.insert(offset, "00.000");
                    case 5: // is legal to omit seconds
                        sb.insert(offset, ":00.000");
                    }
                    dateStr = sb.toString();
                }
                df = _formatISO8601;
                formatStr = DATE_FORMAT_STR_ISO8601;
                if (_formatISO8601 == null) {
                    df = _formatISO8601 = _cloneFormat(DATE_FORMAT_ISO8601, formatStr,
                            _timezone, _locale, _lenient);
                }
            } else {
                // If not, plain date, no timezone
                StringBuilder sb = new StringBuilder(dateStr);
                int timeLen = len - dateStr.lastIndexOf('T') - 1;
                // And possible also millisecond part if missing
                if (timeLen < 12) { // missing, or partial
                    switch (timeLen) {
                    case 11: sb.append('0');
                    case 10: sb.append('0');
                    case 9: sb.append('0');
                        break;
                    default:
                        sb.append(".000");
                    }
                }
                sb.append('Z');
                dateStr = sb.toString();
                df = _formatISO8601_z;
                formatStr = DATE_FORMAT_STR_ISO8601_Z;
                if (df == null) {
                    // 10-Jun-2017, tatu: As per [databind#1651], when using this format,
                    //    must use UTC, not whatever is configured as default timezone
                    //    (because we know `Z` identifier is used)
                    df = _formatISO8601_z = _cloneFormat(DATE_FORMAT_ISO8601_Z, formatStr,
                            DEFAULT_TIMEZONE, _locale, _lenient);
                }
            }
        }
        Date dt = df.parse(dateStr, pos);
        // 22-Dec-2015, tatu: With non-lenient, may get null
        if (dt == null) {
            throw new ParseException
            (String.format("Can not parse date \"%s\": while it seems to fit format '%s', parsing fails (leniency? %s)",
                           dateStr, formatStr, _lenient),
               pos.getErrorIndex());
        }
        return dt;
    }

    protected Date parseAsRFC1123(String dateStr, ParsePosition pos)
    {
        if (_formatRFC1123 == null) {
            _formatRFC1123 = _cloneFormat(DATE_FORMAT_RFC1123, DATE_FORMAT_STR_RFC1123,
                    _timezone, _locale, _lenient);
        }
        return _formatRFC1123.parse(dateStr, pos);
    }

    private final static boolean hasTimeZone(String str)
    {
        // Only accept "+hh", "+hhmm" and "+hh:mm" (and with minus), so
        int len = str.length();
        if (len >= 6) {
            char c = str.charAt(len-6);
            if (c == '+' || c == '-') return true;
            c = str.charAt(len-5);
            if (c == '+' || c == '-') return true;
            c = str.charAt(len-3);
            if (c == '+' || c == '-') return true;
        }
        return false;
    }

    private final static DateFormat _cloneFormat(DateFormat df, String format,
            TimeZone tz, Locale loc, Boolean lenient)
    {
        if (!loc.equals(DEFAULT_LOCALE)) {
            df = new SimpleDateFormat(format, loc);
            df.setTimeZone((tz == null) ? DEFAULT_TIMEZONE : tz);
        } else {
            df = (DateFormat) df.clone();
            if (tz != null) {
                df.setTimeZone(tz);
            }
        }
        if (lenient != null) {
            df.setLenient(lenient.booleanValue());
        }
        return df;
    }

    protected void _clearFormats() {
        _formatRFC1123 = null;
        _formatISO8601 = null;
        _formatISO8601_z = null;

        _formatPlain = null;
    }
}

