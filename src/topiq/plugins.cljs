(ns topiq.plugins
  (:require [topiq.db :refer [hashtag-regexp]]
            [markdown.core :as md]
            [clojure.string :as str]))


;; static pipline for now, will be factored out for js and runtime config later


(def pre-markdown-plugins identity)


(defn special-chars [s]
  (clojure.string/replace s #"\'" "&rsquo;"))

(defn replace-hashtags
  "Replace hashtags in string with html references"
  [s]
  (str/replace s hashtag-regexp "$1<a href='#h=$2'>$2</a>"))

(defn img-responsive [s]
  (str/replace s "<img " "<img class=\"img-responsive\""))


(defn new-tab-link [s]
  (str/replace s "<a " "<a target='_blank'"))


(defn post-markdown-plugins [s]
  (-> s
      replace-hashtags
      img-responsive
      new-tab-link))

(defn render-content [s]
  (-> s
      pre-markdown-plugins
      md/md->html
      post-markdown-plugins))
