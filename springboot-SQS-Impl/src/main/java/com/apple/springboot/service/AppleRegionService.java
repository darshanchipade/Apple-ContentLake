package com.apple.springboot.service;

import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
public class AppleRegionService {

    private final Map<String, RegionInfo> pathMap = new HashMap<>();

    public AppleRegionService() {
        // Initialize with data derived from https://www.apple.com/choose-country-region/
        // Format: path segment -> {geo, locale}

        // North America
        addRegion("us", "US", "en_US");
        addRegion("ca", "CA", "en_CA");
        addRegion("ca/fr", "CA", "fr_CA");
        addRegion("mx", "MX", "es_MX");

        // Asia Pacific
        addRegion("jp", "JP", "ja_JP");
        addRegion("kr", "KR", "ko_KR");
        addRegion("au", "AU", "en_AU");
        addRegion("cn", "CN", "zh_CN");
        addRegion("hk", "HK", "zh_HK");
        addRegion("hk/en", "HK", "en_HK");
        addRegion("tw", "TW", "zh_TW");
        addRegion("sg", "SG", "en_SG");
        addRegion("in", "IN", "en_IN");
        addRegion("th", "TH", "th_TH");
        addRegion("id", "ID", "id_ID");
        addRegion("my", "MY", "ms_MY");
        addRegion("nz", "NZ", "en_NZ");
        addRegion("ph", "PH", "en_PH");
        addRegion("vn", "VN", "vi_VN");

        // Europe
        addRegion("uk", "GB", "en_GB");
        addRegion("fr", "FR", "fr_FR");
        addRegion("de", "DE", "de_DE");
        addRegion("it", "IT", "it_IT");
        addRegion("es", "ES", "es_ES");
        addRegion("nl", "NL", "nl_NL");
        addRegion("be", "BE", "nl_BE");
        addRegion("befr", "BE", "fr_BE");
        addRegion("chde", "CH", "de_CH");
        addRegion("chfr", "CH", "fr_CH");
        addRegion("at", "AT", "de_AT");
        addRegion("se", "SE", "sv_SE");
        addRegion("no", "NO", "no_NO");
        addRegion("dk", "DK", "da_DK");
        addRegion("fi", "FI", "fi_FI");
        addRegion("ie", "IE", "en_IE");
        addRegion("pt", "PT", "pt_PT");
        addRegion("pl", "PL", "pl_PL");
        addRegion("ru", "RU", "ru_RU");
        addRegion("tr", "TR", "tr_TR");

        // Middle East / Africa
        addRegion("ae", "AE", "en_AE");
        addRegion("ae-ar", "AE", "ar_AE");
        addRegion("sa", "SA", "en_SA");
        addRegion("sa-ar", "SA", "ar_SA");
        addRegion("za", "ZA", "en_ZA");
        addRegion("il", "IL", "he_IL");

        // Latin America
        addRegion("br", "BR", "pt_BR");
        addRegion("cl", "CL", "es_CL");
        addRegion("co", "CO", "es_CO");

        // Special / WW
        addRegion("ww", "WW", "en_US");
        addRegion("en_WW", "WW", "en_US");
    }

    private void addRegion(String path, String geo, String locale) {
        pathMap.put(path, new RegionInfo(geo, locale));
    }

    public RegionInfo getRegionInfo(String segment) {
        // Direct match
        if (pathMap.containsKey(segment)) {
            return pathMap.get(segment);
        }

        // Handle case where segment might already be a locale like en_US
        if (segment.contains("_") && segment.length() == 5) {
            String geo = segment.substring(3).toUpperCase();
            return new RegionInfo(geo, segment);
        }

        return null;
    }

    public static class RegionInfo {
        public final String geo;
        public final String locale;

        public RegionInfo(String geo, String locale) {
            this.geo = geo;
            this.locale = locale;
        }
    }
}
