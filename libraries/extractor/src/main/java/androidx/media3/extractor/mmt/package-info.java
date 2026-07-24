/*
 * Copyright 2024 The Android Open Source Project
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
/**
 * Support for the MMT (MPEG Media Transport) over TLV (Type-Length-Value) transport stack used by
 * ISDB-S3 based 4K/8K broadcasting (for example NHK BS4K/BS8K).
 *
 * <p>The transport is layered as follows:
 *
 * <pre>
 *   TLV packets
 *     -&gt; IPv4 / IPv6 / header-compressed IP datagrams (UDP)
 *       -&gt; MMTP packets
 *         -&gt; MPU / MFU fragments      -&gt; HEVC / AVC access units
 *         -&gt; MMT signaling messages   -&gt; PA message / MMT Package Table (asset discovery)
 * </pre>
 */
@NonNullApi
package androidx.media3.extractor.mmt;

import androidx.media3.common.util.NonNullApi;
