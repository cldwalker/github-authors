(ns github-authors.github
  (:require [tentacles.repos :refer [user-repos contributors specific-repo]]
            [tentacles.issues :as issues]
            [io.pedestal.service.log :as log]
            [com.github.ragnard.hamelito.hiccup :as haml]
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
  (github-api-call user-repos user (assoc (gh-auth) :all-pages true)))

(defn average [num denom]
  (format "%.2f"
          (if (zero? denom) 0.0 (/ num (float denom)))))

(defn ->repo [open closed repo]
  (let [all-issues (into open closed)
        total (count all-issues)]
    {:full-name (:full_name repo)
     :resolved (format "%s/%s" (count closed) total)
     :answered (format "%s/%s" (count (filter #(pos? (:comments %)) open)) (count open))
     :pull-requests (format "%s/%s" (count (filter #(get-in % [:pull_request :html_url]) all-issues)) total)
     :last-issue-created-at (->> all-issues (sort-by :created_at) last :created_at)
     :comments-average (average (->> all-issues (map :comments) (apply +)) (count all-issues))
     :last-pushed-at (:pushed_at repo)
     :stars (:watchers repo)
     ;:hours (-> (clj-time.core/interval (clj-time.format/parse
     ;"2013-04-24T18:53:00Z") (clj-time.format/parse
     ;"2013-04-28T05:11:48Z")) clj-time.core/in-minutes (/ 60.0) (/
                                        ;24.0))
                                        ;:desc (:description repo)
                                        ;:user user
     }))

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

(defn- render-row [repo-map]
  (haml/html
   (clostache/render-resource
    "public/row.haml"
    repo-map)))

(defn- render-end-msg
  "Build final message summarizing contributions to a user's forks."
  [user repos]
  (format
   "<a href=\"https://github.com/%s\">%s</a> has authored %s repositories."
   user
   user
   (count repos)))

(defn- fetch-repo-and-send-row [send-to user repo]
  (let [repo-map (memoized-fetch-repo-info user repo)]
    (send-to "results" (render-row repo-map))
    repo-map))

(defn- stream-contributions*
  "Sends 3 different sse events (message, results, end-message) depending on
what part of the page it's updating."
  [send-event-fn sse-context user]
  (let [repos (memoized-fetch-repos user)
        author-repos (filter-bug (remove :fork repos))
        send-to (partial send-event-fn sse-context)]
    (send-to "message"
             (format "%s has %s authored repos. Fetching data..."
                     user (count author-repos)))
    (->> author-repos
         (mapv (partial fetch-repo-and-send-row send-to user))
         (render-end-msg user)
         (send-to "message"))
    (send-to "end-message" user)))

(defn stream-contributions
  "Streams a user's contributions with a given fn and sse-context."
  [send-event-fn sse-context user]
  (if user
    (try
      (stream-contributions* send-event-fn sse-context user)
      {:status 200}
      (catch clojure.lang.ExceptionInfo exception
        (log/error :msg (str "40X response from Github: " (pr-str (ex-data exception))))
        (send-event-fn sse-context "error"
                       (if (= :github-client-error (:reason (ex-data exception)))
                         (.getMessage exception)
                         "An unexpected error occurred while contacting Github. Please try again later."))))
    (log/error :msg "No user given to fetch contributions. Ignored.")))
