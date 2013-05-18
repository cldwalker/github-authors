(ns github-authors.util
  "Misc utilities"
  (:require clj-time.core
            clj-time.format))

(defn get-in!
  "Fail fast if no truish value for get-in"
  [m ks]
  (or (get-in m ks) (throw (ex-info "No value found for nested keys in map" {:map m :keys ks}))))

(defn get!
  "Fail fast if no truish value for get"
  [m k]
  (or (get m k) (throw (ex-info "No value found for key in map" {:map m :key k}))))


(defn average [num denom]
  (format "%.2f"
          (if (zero? denom) 0.0 (/ num (float denom)))))

(defn difference-in-hours
  [start end]
  (clj-time.core/in-hours
   (clj-time.core/interval (clj-time.format/parse start) (clj-time.format/parse end))))

(defn format-date [date]
  (when date
    (clj-time.format/unparse (clj-time.format/formatters :year-month-day) (clj-time.format/parse date))))