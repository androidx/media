/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common.util;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import androidx.annotation.RequiresOptIn;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Signifies that a public API (class, method or field) is still under development.
 *
 * <p>This means the API may have known issues or limitations, and is likely to undergo incompatible
 * changes or removal in a future release.
 *
 * <p>This is similar to {@link UnstableApi}, but is applied to symbols that are much more likely to
 * change than {@link UnstableApi} symbols. The note in the {@link UnstableApi} docs about safe and
 * unsafe usages (from apps and libraries respectively) applies to symbols with this annotation too.
 */
@Documented
@Retention(CLASS)
@Target({TYPE, METHOD, CONSTRUCTOR, FIELD})
@UnstableApi
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
public @interface ExperimentalApi {}
