(ns nebsearch.compression
  "LZ4 compression wrapper for B-tree node storage.

  LZ4 provides:
  - Fast compression: ~500 MB/s
  - Very fast decompression: ~3000 MB/s (6x faster!)
  - Good compression ratio: ~2-2.5x
  - Minimal CPU overhead: ~5-10%

  Perfect for read-heavy workloads where decompression speed matters."
  #?(:clj (:import [net.jpountz.lz4 LZ4Factory LZ4Compressor LZ4SafeDecompressor]
                   [java.util Arrays])))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (do
     ;; Use fastest native instance (falls back to pure Java if native not available)
     (def ^LZ4Factory factory (LZ4Factory/fastestInstance))
     (def ^LZ4Compressor compressor (.fastCompressor factory))
     (def ^LZ4SafeDecompressor decompressor (.safeDecompressor factory))

     (defn compress
       "Compress byte array with LZ4.

       Returns compressed byte array (exact size, no padding).
       Original size must be stored separately for decompression."
       [^bytes data]
       (let [original-size (alength data)
             max-compressed-length (.maxCompressedLength compressor original-size)
             compressed (byte-array max-compressed-length)
             compressed-size (.compress compressor
                                       data 0 original-size
                                       compressed 0 max-compressed-length)]
         ;; Return only the used portion (no padding)
         (Arrays/copyOf compressed compressed-size)))

     (defn decompress
       "Decompress LZ4 byte array.

       Parameters:
       - compressed: The compressed byte array
       - original-size: Original uncompressed size (must be exact!)

       Returns decompressed byte array."
       [^bytes compressed ^long original-size]
       (let [decompressed (byte-array original-size)]
         (.decompress decompressor
                     compressed 0 (alength compressed)
                     decompressed 0 original-size)
         decompressed))

     (defn compression-ratio
       "Calculate compression ratio.
       Returns ratio > 1.0 for successful compression."
       [original-size compressed-size]
       (/ (double original-size) (double compressed-size))))

   ;; ClojureScript stubs (compression not implemented)
   :cljs
   (do
     (defn compress [data]
       (throw (ex-info "Compression not supported in ClojureScript" {})))

     (defn decompress [compressed original-size]
       (throw (ex-info "Compression not supported in ClojureScript" {})))

     (defn compression-ratio [original-size compressed-size]
       (/ (double original-size) (double compressed-size)))))
