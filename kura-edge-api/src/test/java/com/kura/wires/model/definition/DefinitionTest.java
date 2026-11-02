package com.kura.wires.model.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * metatype 定义（Ocd/Ad/AdOption）行为验证。
 */
class DefinitionTest {

    @Test
    void buildOcdWithAds() {
        final Ad ad = new Ad("interval", "间隔", Scalar.INTEGER, true, 30);
        ad.setDescription("发射间隔");
        ad.setMin(1d);
        ad.setMax(100d);
        ad.setOptions(List.of(new AdOption("秒", "SECONDS")));

        final Ocd ocd = new Ocd("org.eclipse.kura.wire.Timer", "定时器", "周期发射", List.of(ad));
        assertThat(ocd.getId()).isEqualTo("org.eclipse.kura.wire.Timer");
        assertThat(ocd.getAds()).hasSize(1);
        assertThat(ocd.getAds().get(0).getType()).isEqualTo(Scalar.INTEGER);
        assertThat(ocd.getAds().get(0).getMin()).isEqualTo(1d);
        assertThat(ocd.getAds().get(0).getOptions()).hasSize(1);
    }

    @Test
    void tolerateNullAdsAndOptions() {
        final Ocd ocd = new Ocd("id", "name", "desc", null);
        assertThat(ocd.getAds()).isEmpty();
        ocd.setAds(null);
        assertThat(ocd.getAds()).isEmpty();

        final Ad ad = new Ad();
        ad.setOptions(null);
        assertThat(ad.getOptions()).isEmpty();
        assertThat(ad.getType()).isEqualTo(Scalar.STRING);
        assertThat(ad.isRequired()).isFalse();
    }

    @Test
    void defaultScalarCoversUpstreamSet() {
        assertThat(Scalar.values()).contains(Scalar.STRING, Scalar.LONG, Scalar.DOUBLE, Scalar.FLOAT,
                Scalar.INTEGER, Scalar.BOOLEAN, Scalar.CHAR, Scalar.BYTE, Scalar.PASSWORD, Scalar.PIN, Scalar.SHORT);
    }

    @Test
    void exposeAdAndAdOptionAccessors() {
        final Ad ad = new Ad();
        ad.setId("interval");
        ad.setName("间隔");
        ad.setType(Scalar.LONG);
        ad.setRequired(true);
        ad.setDefaultValue(10);
        ad.setMin(0d);
        ad.setMax(9d);
        assertThat(ad.getId()).isEqualTo("interval");
        assertThat(ad.getName()).isEqualTo("间隔");
        assertThat(ad.getType()).isEqualTo(Scalar.LONG);
        assertThat(ad.isRequired()).isTrue();
        assertThat(ad.getDefaultValue()).isEqualTo(10);
        assertThat(ad.getMin()).isEqualTo(0d);
        assertThat(ad.getMax()).isEqualTo(9d);

        final AdOption option = new AdOption();
        option.setLabel("秒");
        option.setValue("SECONDS");
        assertThat(option.getLabel()).isEqualTo("秒");
        assertThat(option.getValue()).isEqualTo("SECONDS");
        assertThat(new AdOption("分", "MINUTES").getLabel()).isEqualTo("分");
    }

    @Test
    void exposeOcdAccessors() {
        final Ocd ocd = new Ocd();
        ocd.setId("id2");
        ocd.setName("name2");
        ocd.setDescription("desc2");
        ocd.setAds(List.of(new Ad()));
        assertThat(ocd.getId()).isEqualTo("id2");
        assertThat(ocd.getName()).isEqualTo("name2");
        assertThat(ocd.getDescription()).isEqualTo("desc2");
        assertThat(ocd.getAds()).hasSize(1);
    }
}
