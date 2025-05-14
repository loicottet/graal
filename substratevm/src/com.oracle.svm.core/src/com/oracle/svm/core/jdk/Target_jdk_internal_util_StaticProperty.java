/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jdk;

import java.util.Objects;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * This class provides JDK-internal access to values that are also available via system properties.
 * However, it must not return values changed by the user. We do not want to query the values during
 * VM startup, because doing that is expensive. So we perform lazy initialization by accessing the
 * corresponding system properties.
 * <p>
 * We {@link Substitute substitute} the whole class so that it is possible to use a custom static
 * constructor at run-time. If this class is used before the system properties are fully parsed and
 * initialized, it can happen that we return or cache invalid values (see GR-64572).
 * <p>
 * Note for updating: use {@link Delete} for static fields that should be unreachable (e.g, because
 * we substituted an accessor and the field is therefore unused). Use {@link Alias} for static
 * fields that can be initialized in our custom static constructor. Use {@link Substitute} for
 * methods that access expensive lazily initialized system properties (see
 * {@link SystemPropertiesSupport} for a list of all lazily initialized properties).
 */
@Substitute
@TargetClass(jdk.internal.util.StaticProperty.class)
@SuppressWarnings({"unused", "FieldCanBeLocal"})
final class Target_jdk_internal_util_StaticProperty {
    // Checkstyle: stop
    @Delete//
    private static String JAVA_HOME;

    @Delete//
    private static String USER_HOME;

    @Delete//
    private static String USER_DIR;

    @Delete//
    private static String USER_NAME;

    @Delete//
    private static String JAVA_LIBRARY_PATH;

    @Alias//
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)//
    private static String SUN_BOOT_LIBRARY_PATH;

    @Alias//
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)//
    private static String JDK_SERIAL_FILTER;

    @Alias//
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)//
    private static String JDK_SERIAL_FILTER_FACTORY;

    @Delete//
    private static String JAVA_IO_TMPDIR;

    @Alias//
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)//
    private static String NATIVE_ENCODING;

    /*
     * This static constructor is executed at run-time. Be careful that it only initializes lazy
     * system properties that are reasonably cheap to initialize.
     */
    static {
        if (!SubstrateUtil.HOSTED) {
            SystemPropertiesSupport p = SystemPropertiesSupport.singleton();
            SUN_BOOT_LIBRARY_PATH = p.getInitialProperty("sun.boot.library.path", "");
            JDK_SERIAL_FILTER = p.getInitialProperty("jdk.serialFilter");
            JDK_SERIAL_FILTER_FACTORY = p.getInitialProperty("jdk.serialFilterFactory");
            NATIVE_ENCODING = p.getInitialProperty("native.encoding");
        }
    }

    @Substitute
    private static String javaHome() {
        return SystemPropertiesSupport.singleton().getInitialProperty("java.home");
    }

    @Substitute
    private static String userHome() {
        return SystemPropertiesSupport.singleton().getInitialProperty(UserSystemProperty.HOME);
    }

    @Substitute
    private static String userDir() {
        return SystemPropertiesSupport.singleton().getInitialProperty(UserSystemProperty.DIR);
    }

    @Substitute
    private static String userName() {
        return SystemPropertiesSupport.singleton().getInitialProperty(UserSystemProperty.NAME);
    }

    @Substitute
    private static String javaLibraryPath() {
        return SystemPropertiesSupport.singleton().getInitialProperty("java.library.path", "");
    }

    @Substitute
    private static String javaIoTmpDir() {
        return SystemPropertiesSupport.singleton().getInitialProperty("java.io.tmpdir");
    }

    @Substitute
    public static String sunBootLibraryPath() {
        assert Objects.equals(SUN_BOOT_LIBRARY_PATH, SystemPropertiesSupport.singleton().getInitialProperty("sun.boot.library.path", ""));
        return SUN_BOOT_LIBRARY_PATH;
    }

    @Substitute
    public static String jdkSerialFilter() {
        assert Objects.equals(JDK_SERIAL_FILTER, SystemPropertiesSupport.singleton().getInitialProperty("jdk.serialFilter"));
        return JDK_SERIAL_FILTER;
    }

    @Substitute
    public static String jdkSerialFilterFactory() {
        assert Objects.equals(JDK_SERIAL_FILTER_FACTORY, SystemPropertiesSupport.singleton().getInitialProperty("jdk.serialFilterFactory"));
        return JDK_SERIAL_FILTER_FACTORY;
    }

    @Substitute
    public static String nativeEncoding() {
        assert Objects.equals(NATIVE_ENCODING, SystemPropertiesSupport.singleton().getInitialProperty("native.encoding"));
        return NATIVE_ENCODING;
    }
}
