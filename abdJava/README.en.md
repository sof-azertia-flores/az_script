# ABD Java Data Model

[中文](README.md) | English

This module compiles with Java 17 and depends on Gson 2.11.0. See the [C++ data-model guide](../abdC/README.en.md) for cross-language framing, tags, and integration. Gradle `check` runs the JUnit-independent `azertia.binary.AbdRegression`; the root script can also compile and run it offline.

## Reading and types

`AbdValue.fromAbd(bytes)` reads exactly one complete frame and rejects trailing garbage. `fromAbd(bytes, offset, length)` reads the first frame in a bounded range, suitable for concatenated frames. `getAsAss()` checks boundaries frame by frame without copying all remaining input for each entry. Both the `AbdValue` constructor and `getData()` copy bytes to prevent implicit external mutation.

`AcsObject` preserves insertion order. Objects and arrays share type decoding; unknown tags and duplicate keys do not silently disappear. Floating-point encoding is little-endian, matching C++, and preserves negative zero and NaN payloads. `AcsByteArray` / `put(key, byte[])` use the new `0xce200a` tag, supporting empty arrays and leading zeros. Old `0xce2009` values still decode as BigInteger and preserve original input bytes if the numeric value is unchanged. Bytes already discarded by older BigInteger conversion cannot be recovered.

`AcsAddress` uses the independent `0xce200b` tag and exactly eight little-endian bytes for an unsigned value. `new AcsAddress(long)` preserves every bit of the Java long; String and BigInteger constructors require 0 through `18446744073709551615`. `rawBits()` returns the raw long pattern; `toUnsignedString()` and `toBigInteger()` provide unsigned access. `AcsObject.getAsAddress(key)` preserves the address type. JSON uses `{"address":"18446744073709551615"}` to distinguish addresses from int32 values and ordinary strings.

## Java reflective structures

`AsStructIO` is a separate Java object-mapping layer, not `AcsObject`. Historical encoding is retained: int is little-endian; other multibyte Java primitives are big-endian. Annotated fields follow declaration order from base to derived classes. `@AsColum(order=N)` specifies an exact one-based position; other fields fill gaps. Duplicate or out-of-range positions are errors. Map/List interface fields can decode as `LinkedHashMap`/`ArrayList`; field counts and scalar lengths are strictly checked.

The old structure format has no null marker: null, empty strings, empty bytes, and empty collections were all written as zero length. The new implementation prioritizes valid empty strings, bytes, and collections; zero length for nullable numbers and nonempty structures still reads as null. Old files cannot recover this distinction. Add an explicit `hasValue` field when these states must differ; do not rely on ambiguous legacy encoding.

The original format carries no Number subclass information. Fields declared `Number` support only Integer; explicitly declare long/float/double/BigInteger fields. `AcsObject` has explicit tags and is suitable for cross-language numbers and script data requiring exact types.

`compatMode=true` and `AcsWrappedObject` retain Java reflection/Serializable compatibility for trusted application objects only. They may instantiate input-declared classes and are not a sandbox for untrusted scripts. Reads also obey JVM serialization filters and depth/object-count/array-size limits. Use basic ACS types for ordinary cross-language data.

Regressions cover a 15-field cross-language fixture, every frame truncation point, corrupt lengths/types, cyclic and excessively nested structures, raw bytes, field ordering, BigInteger, interface collections, empty strings, reflection, and explicit Serializable compatibility. The test main accepts `--write PATH` and `--read PATH`; reads verify every field and require byte-identical re-encoding.
