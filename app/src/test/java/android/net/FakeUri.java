package android.net;

import android.os.Parcel;

import java.util.Collections;
import java.util.List;

public final class FakeUri extends Uri {
    private final String rawValue;

    public FakeUri(String rawValue) {
        this.rawValue = rawValue;
    }

    @Override
    public boolean isHierarchical() {
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String getScheme() {
        int separator = rawValue.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        return rawValue.substring(0, separator);
    }

    @Override
    public String getSchemeSpecificPart() {
        int separator = rawValue.indexOf(':');
        if (separator < 0 || separator + 1 >= rawValue.length()) {
            return "";
        }
        return rawValue.substring(separator + 1);
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return getSchemeSpecificPart();
    }

    @Override
    public String getAuthority() {
        return null;
    }

    @Override
    public String getEncodedAuthority() {
        return null;
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return null;
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return null;
    }

    @Override
    public String getEncodedPath() {
        return null;
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.emptyList();
    }

    @Override
    public String getLastPathSegment() {
        return null;
    }

    @Override
    public String toString() {
        return rawValue;
    }

    @Override
    public Builder buildUpon() {
        throw new UnsupportedOperationException("Not needed in tests");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(rawValue);
    }
}
