(ns google-apps-clj.google-drive.mime-types
  "Documentation of the supported custom Google Drive mime-types
  (from https://developers.google.com/drive/v3/web/mime-types)"
  (:require [clojure.core.typed :as t]))

#_:clj-kondo/ignore
(t/def audio        :- t/Str "application/vnd.google-apps.audio")
#_:clj-kondo/ignore
(t/def document     :- t/Str "application/vnd.google-apps.document")
#_:clj-kondo/ignore
(t/def drawing      :- t/Str "application/vnd.google-apps.drawing")
#_:clj-kondo/ignore
(t/def file         :- t/Str "application/vnd.google-apps.file")
#_:clj-kondo/ignore
(t/def folder       :- t/Str "application/vnd.google-apps.folder")
#_:clj-kondo/ignore
(t/def form         :- t/Str "application/vnd.google-apps.form")
#_:clj-kondo/ignore
(t/def fusion-table :- t/Str "application/vnd.google-apps.fusiontable")
#_:clj-kondo/ignore
(t/def map-custom   :- t/Str "application/vnd.google-apps.map")
#_:clj-kondo/ignore
(t/def photo        :- t/Str "application/vnd.google-apps.photo")
#_:clj-kondo/ignore
(t/def presentation :- t/Str "application/vnd.google-apps.presentation")
#_:clj-kondo/ignore
(t/def apps-script  :- t/Str "application/vnd.google-apps.script")
#_:clj-kondo/ignore
(t/def sites        :- t/Str "application/vnd.google-apps.sites")
#_:clj-kondo/ignore
(t/def spreadsheet  :- t/Str "application/vnd.google-apps.spreadsheet")
#_:clj-kondo/ignore
(t/def unknown      :- t/Str "application/vnd.google-apps.unknown")
#_:clj-kondo/ignore
(t/def video        :- t/Str "application/vnd.google-apps.video")
