(ns github-authors.github-test
  (:require [clojure.test :refer :all]
            [github-authors.service :as service]
            [bond.james :as bond :refer [with-spy]]
            [tentacles.repos :as repos]
            [github-authors.fixtures :as fixtures]
            [github-authors.test-helper :as test-helper]
            [github-authors.github :as github]))

(defn send-event-fn [& args])
(def sse-context {:request {}})

(defn stream-repositories
  []
  (test-helper/disallow-web-requests!
   (github/stream-repositories send-event-fn sse-context "defunkt")))

(defn verify-args-called-for [f & expected-args]
  (doseq [[expected actual] (map vector expected-args (->> f bond/calls (map :args)))]
    (is (= expected actual))))

(deftest stream-repositories-receives-403-from-github
  []
  (with-redefs [repos/user-repos (constantly fixtures/response-403)
                github/gh-auth (constantly {})]
    (with-spy [send-event-fn]
      (stream-repositories)
      (verify-args-called-for
       send-event-fn
       [sse-context "error" "Rate limit has been exceeded for Github's API. Please try again later."]))))

(deftest stream-repositories-receives-404-from-github
  []
  (with-redefs [repos/user-repos (constantly fixtures/response-404)
                github/gh-auth (constantly {})]
    (with-spy [send-event-fn]
      (stream-repositories)
      (verify-args-called-for
       send-event-fn
       [sse-context "error" "Received a 404 from Github. Please try again later."]))))

;; because conjure only supports =
(def expected-row
  "<tr class=\"contribution\"><td><a href=\"https://github.com/ajaxorg/ace\">ajaxorg/ace</a><span class=\"fork\">&nbsp;(<a href=\"https://github.com/defunkt/ace\" title=\"defunkt/ace\">fork</a>)</span><span class=\"stars\">&nbsp;5188 stars</span></td><td><a href=\"https://github.com/ajaxorg/ace/commits?author=defunkt\">5 commits</a></td><td><a class=\"ranking \" href=\"https://github.com/ajaxorg/ace/contributors\">1st of 2</a></td><td>Ajax.org Cloud9 Editor</td></tr>")

#_(deftest stream-repositories-receives-200s-from-github
  []
  (with-redefs [repos/user-repos (constantly fixtures/response-user-repos)
                repos/specific-repo (constantly fixtures/response-specific-repo)
                repos/contributors (constantly fixtures/response-contributors)
                github/gh-auth (constantly {})]
    (mocking [send-event-fn]
             (stream-repositories)
             (verify-nth-call-args-for 1 send-event-fn sse-context "message"
                                       "defunkt has 1 forks. Fetching data...")
             (verify-nth-call-args-for 2 send-event-fn sse-context "results" expected-row)
             (verify-nth-call-args-for 3 send-event-fn sse-context "message"
                                       "<a href=\"https://github.com/defunkt\">defunkt</a> has contributed to 1 of 1 forks.")
             (verify-nth-call-args-for 4 send-event-fn sse-context "end-message" "defunkt"))))
