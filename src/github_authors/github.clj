(ns github-authors.github
  (:require [tentacles.repos :refer [user-repos]]
            [tentacles.issues :as issues]
            [io.pedestal.service.log :as log]
            [com.github.ragnard.hamelito.hiccup :as haml]
            clj-time.core
            clj-time.format
            [clostache.parser :as clostache]))

;;; helpers
(defn gh-auth
  "Sets github authentication using $GITHUB_AUTH. Its value can be a basic auth user:pass
or an oauth token."
  []
  (if-let [auth (System/getenv "GITHUB_AUTH")]
    (if (.contains auth ":") {:auth auth} {:oauth-token auth})
    (or (throw (ex-info "Set $GITHUB_AUTH to an oauth token or basic auth in order to use Github's api." {})))))

(defn get-in!
  "Fail fast if no truish value for get-in"
  [m ks]
  (or (get-in m ks) (throw (ex-info "No value found for nested keys in map" {:map m :keys ks}))))

(defn get!
  "Fail fast if no truish value for get"
  [m k]
  (or (get m k) (throw (ex-info "No value found for key in map" {:map m :key k}))))

;;; API calls
(defn- github-api-call
  "Wraps github api calls to handle unsuccessful responses."
  [f & args]
  (let [response (apply f args)]
    (if (some #{403 404} [(:status response)])
      (throw (ex-info
              (if (= 403 (:status response))
                "Rate limit has been exceeded for Github's API. Please try again later."
                (format "Received a %s from Github. Please try again later." (:status response)))
              {:reason :github-client-error :response response}))
      response)))

;; filter eliminates tentacles bug, extra {} after each call
(defn filter-bug [s]
  (vec (filter seq s)))

(defn fetch-issues [user repo state]
  (let [ret (filter-bug (github-api-call issues/issues user repo (assoc (gh-auth) :state state :all-pages true)))]
    (if (= ret [[:message "Issues are disabled for this repo"]]) [] ret)))

(defn fetch-repos
  "Fetch all public repositories for a user"
  [user]
  (filter-bug (github-api-call user-repos user (assoc (gh-auth) :all-pages true))))

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

(defn ->repo [open closed repo]
  (let [all-issues (into open closed)
        total (count all-issues)
        comments (->> all-issues (map :comments) (apply +))
        days-to-resolve (->> closed
                             (map #(/ (difference-in-hours (:created_at %) (:closed_at %)) 24.0))
                             (apply +))]
    {:full-name (:full_name repo)
     :count-total total
     :count-closed (count closed)
     :count-open (count open)
     :count-answered (count (filter #(pos? (:comments %)) open))
     :count-pull-requests (count (filter #(get-in % [:pull_request :html_url]) all-issues))
     :count-days-to-resolve days-to-resolve
     :count-comments comments
     :last-issue-created-at (format-date (->> all-issues (sort-by :created_at) last :created_at))
     :comments-average (average comments (count all-issues))
     :last-pushed-at (format-date (:pushed_at repo))
     :stars (:watchers repo)
     :days-to-resolve-average (average days-to-resolve (count closed))}))

(defn fetch-repo-info [user repo]
  (let [repo-name (get! repo :name)]
    (log/info :msg (format "Fetching repo info for %s/%s" user repo-name))
    (->repo (fetch-issues user repo-name "open")
            (fetch-issues user repo-name "closed")
            repo)))

;;; Cache api calls for maximum reuse. Of course, this cache only lasts
;;; as long as the app lives.
(def memoized-fetch-repo-info (memoize fetch-repo-info))
(def memoized-fetch-repos (memoize fetch-repos))

(defn- render-haml
  [template repo-map]
  (haml/html
   (clostache/render-resource template repo-map)))

(defn calculate-total-row
  [repos]
  (let [active-repos (remove #(zero? (:count-total %)) repos)
        sum #(apply + (map % active-repos))]
    {:count-total (sum :count-total)
     :count-closed (sum :count-closed)
     :count-open (sum :count-open)
     :count-answered (sum :count-answered)
     :count-pull-requests (sum :count-pull-requests)
     :comments-average (average (sum :count-comments) (sum :count-total))
     :days-to-resolve-average (average (sum :count-days-to-resolve) (sum :count-closed))}))

(defn- render-end-msg
  "Build final message summarizing user repositories."
  [send-to user repos]
  (send-to
   "results"
   (render-haml "public/total.haml" (calculate-total-row repos)))

  (send-to
   "message"
   (format
    "<a href=\"https://github.com/%s\">%s</a> has authored %s repositories. See their <a href=\"#total-stats\">total stats</a>."
    user
    user
    (count repos))))

(defn- fetch-repo-and-send-row [send-to user repo]
  (let [repo-map (memoized-fetch-repo-info user repo)]
    (send-to "results" (render-haml "public/row.haml" repo-map))
    repo-map))

(defn- stream-repositories*
  "Sends 3 different sse events (message, results, end-message) depending on
what part of the page it's updating."
  [send-event-fn sse-context user]
  (let [repos (memoized-fetch-repos user)
        author-repos (remove :fork repos)
        send-to (partial send-event-fn sse-context)]
    (send-to "message"
             (format "%s has %s authored repos. Fetching data..."
                     user (count author-repos)))
    (->> author-repos
         (mapv (partial fetch-repo-and-send-row send-to user))
         (render-end-msg send-to user))
    (send-to "end-message" user)))

(defn stream-repositories
  "Streams a user's repositories with a given fn and sse-context."
  [send-event-fn sse-context user]
  (if user
    (try
      (stream-repositories* send-event-fn sse-context user)
      {:status 200}
      (catch clojure.lang.ExceptionInfo exception
        (log/error :msg (str "40X response from Github: " (pr-str (ex-data exception))))
        (send-event-fn sse-context "error"
                       (if (= :github-client-error (:reason (ex-data exception)))
                         (.getMessage exception)
                         "An unexpected error occurred while contacting Github. Please try again later."))))
    (log/error :msg "No user given to fetch repositories. Ignored.")))
