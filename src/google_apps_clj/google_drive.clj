(ns google-apps-clj.google-drive
  "A library for connecting to Google Drive through the Drive API"
  (:require [clojure.core.typed :as t]
            [clojure.core.typed.unsafe :as tu]
            [clojure.edn :as edn :only [read-string]]
            [clojure.java.io :as io :only [as-url file resource]]
            [clojure.string :as string]
            [google-apps-clj.credentials :as cred])
  (:import (com.google.api.client.googleapis.batch BatchRequest
                                                   BatchCallback)
           (com.google.api.client.googleapis.batch.json JsonBatchCallback)
           (com.google.api.client.googleapis.json GoogleJsonErrorContainer)
           (com.google.api.client.http FileContent
                                       GenericUrl)
           (com.google.api.client.util GenericData)
           (com.google.api.services.drive Drive
                                          Drive$Builder
                                          Drive$Files$List
                                          Drive$Permissions$List
                                          DriveRequest
                                          DriveScopes)
           (com.google.api.services.drive.model File
                                                FileList
                                                ParentReference
                                                Permission
                                                PermissionId
                                                PermissionList
                                                Property
                                                PropertyList)))

(t/ann ^:no-check clojure.core/slurp [java.io.InputStream -> String])

(t/ann build-drive-service [cred/GoogleCtx -> Drive])
(defn ^Drive build-drive-service
  "Given a google-ctx configuration map, builds a Drive service using
   credentials coming from the OAuth2.0 credential setup inside google-ctx"
  [google-ctx]
  (let [drive-builder (->> google-ctx
                           cred/build-credential
                           (Drive$Builder. cred/http-transport cred/json-factory))]
    (cast Drive (doto (.build drive-builder)
                  assert))))

(t/defalias Query
  '{:type String
    :fields '[Keyword]
    :query (t/Maybe String)
    :file-id (t/Maybe String)})

(t/defalias Request
  (t/U Drive$Files$List
       Drive$Permissions$List))

(t/ann build-request [cred/GoogleCtx Query -> Request])
(defn ^DriveRequest build-request
  [google-ctx query]
  (let [drive (build-drive-service google-ctx)
        {:keys [type fields]} query
        type-fields (case type
                      :file ["nextPageToken"]
                      [])
        item-fields (if (seq fields)
                      [(format "items(%s)"
                               (string/join "," (map name fields)) ")")]
                      ["items"])
        fields (string/join "," (concat type-fields item-fields))]
    (case type
      :file
      (let [{:keys [query]} query]
        (-> (.list (.files drive))
            (.setFields fields)
            (.setMaxResults (int 1))
            (cond-> query (.setQ query))))
      :permissions
      (let [{:keys [file-id]} query]
        (-> (.list (.permissions drive) file-id)
            (.setFields fields))))))

(defprotocol Requestable
  (next-page! [_ response]))

(extend-protocol Executable
  Drive$Files$List
  (next-page! [request ^FileList response]
    (when-let [page-token (.getNextPageToken response)]
      (.setPageToken request page-token)))
  Drive$Permissions$List
  (next-page! [request response]))

(t/ann execute! [cred/GoogleCtx Query -> (t/Vec t/Any)])
(defn execute!
  [google-ctx query]
  (let [request (build-request google-ctx query)
        results (transient [])]
    (loop []
      (let [response ^GenericData (.execute request)]
        (doseq [item (.get response "items")]
          (conj! results item))
        (when (next-page! request response)
          (recur))))
    (persistent! results)))

(defn execute-batch*!
  [google-ctx requests]
  (let [credential (cred/build-credential google-ctx)
        batch (BatchRequest. cred/http-transport credential)
        responses (into [] (repeatedly (count requests) #(transient [])))]
    (loop [requests (map-indexed vector requests)]
      (let [next-requests (transient {})]
        (doseq [[i ^DriveRequest request] requests]
          (.queue request batch GoogleJsonErrorContainer
                  (reify BatchCallback
                    (onSuccess [_ http-response headers]
                      (let [items (get http-response "items")
                            response (nth responses i)]
                        (doseq [item items]
                          (conj! response item)))
                      (when (next-page! request http-response)
                        (assoc! next-requests i request)))
                    (onFailure [_ error headers]
                      ;; FIXME do better
                      (throw (Exception. "sad"))))))
        (.execute batch)
        (let [next-requests (persistent! next-requests)]
          (when (seq next-requests)
            (recur next-requests)))))
    (mapv persistent! responses)))

(defn execute-batch!
  "Execute the given queries in a batch, returning a vector of the items of
   their responses in the same order as the queries. If any queries in a batch
   yield paginated responses, another batch will be executed for all such
   queries, iteratively until all pages have been received."
  [google-ctx queries]
  (execute-batch*! google-ctx (map (partial build-request google-ctx) queries)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; File Management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/ann ^:no-check get-file-ids [cred/GoogleCtx -> (t/Map String String)])
(defn get-file-ids
  "Given a google-ctx configuration map, gets the file-id and title
   for every file under the users Drive as a map in the structure
   of {file-id file-title}"
  [google-ctx]
  (let [drive-service (build-drive-service google-ctx)
        drive-files (doto (.files ^Drive drive-service)
                      assert)
        files-list (doto (.list drive-files)
                     assert)
        all-files (doto (.getItems (.execute files-list))
                    assert)
        extract-id (fn [file]
                     (let [file-map (into {} file)]
                       {(get file-map "id") (get file-map "title")}))]
    (into {} (map extract-id all-files))))

(t/ann query-files [cred/GoogleCtx String -> (t/Vec File)])
(defn query-files
  "Runs the given query against the given context and returns the results
   as a vector of File objects"
  [google-ctx query]
  ;; The Drive object explicitly disclaims thread-safety, and the contracts
  ;; given by the execute response and items are unclear, so instead of
  ;; concatenating the items, we explicitly copy them into a vector.
  ;;
  ;; We eagerly realize the results to avoid the stack abuse given by the naive
  ;; lazy seq recursive concat approach, as well as to reduce the chance of
  ;; drive mutations affecting the results.
  (let [request (some-> (build-drive-service google-ctx)
                        .files
                        .list
                        (.setQ query))
        results (transient [])]
    request
    #_(loop []
        (let [response (.execute request)]
          (doseq [file (.getItems response)]
            (conj! results file))
          (when-let [page-token (.getNextPageToken response)]
            (.setPageToken request page-token)
            (recur))))
    #_(persistent! results)))

(t/ann get-files [cred/GoogleCtx File -> (t/Vec File)])
(defn get-files
  "Returns a seq of files in the given folder"
  [google-ctx folder]
  (query-files google-ctx
               (str "'" (.getId folder) "' in parents and trashed=false")))

(t/ann folder? [File -> Boolean])
(defn folder?
  "Returns true if the file is a folder"
  [file]
  (= "application/vnd.google-apps.folder" (.getMimeType file)))

(t/ann folder-seq [cred/GoogleCtx File -> (t/Seq File)])
(defn folder-seq
  "Returns a lazy seq of all files in the given folder, including itself, via a
   depth-first traversal"
  [google-ctx folder]
  (tree-seq folder? (partial get-files google-ctx) folder))

(t/ann get-root-files [cred/GoogleCtx -> (t/Vec File)])
(defn get-root-files
  "Given a google-ctx configuration map, gets a seq of files from the user's
   root folder"
  [google-ctx]
  (query-files google-ctx "'root' in parents and trashed=false"))

(t/ann get-file [cred/GoogleCtx String -> File])
(defn get-file
  "Given a google-ctx configuration map and the id of the desired
  file as a string, returns that file as a drive File object"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        drive-files (doto (.files ^Drive drive-service)
                      assert)
        get-file (doto (.get drive-files file-id)
                   assert)]
    (cast File (doto (.execute get-file)
                 assert))))

(t/ann upload-file [cred/GoogleCtx java.io.File String String String String -> File])
(defn upload-file
  "Given a google-ctx configuration map, a file to upload, an ID of
   the parent folder you wish to insert the file in, the title of the
   Drive file, the description of the Drive file, and the MIME type of
   the file, builds a Drive Service and inserts this file into Google
   Drive with permissions of the folder it's inserted into. The owner
   is whomever owns the Credentials used to make the Drive Service"
  [google-ctx file parent-folder-id file-title file-description media-type]
  (let [drive-service (build-drive-service google-ctx)
        parent-folder (doto (ParentReference.)
                        (.setId parent-folder-id))
        drive-file (doto (File.)
                     (.setTitle file-title)
                     (.setDescription file-description)
                     (.setMimeType media-type)
                     (.setParents (vector parent-folder)))
        media-content (FileContent. media-type file)
        drive-files (doto (.files ^Drive drive-service)
                     assert)
        drive-file (doto (.insert drive-files drive-file media-content)
                     assert
                     (.setConvert true))]
    (cast File (doto (.execute drive-file)
                 assert))))

(t/ann create-blank-file [cred/GoogleCtx String String String String -> File])
(defn create-blank-file
  "Given a google-ctx configuration map, an ID of the parent folder you
   wish to insert the file in, the title of the Drive file, the description
   of the Drive file, and the MIME type of the file(which will be converted
   into a google file type, builds a Drive Service and inserts a blank file
   into Google Drive with permissions of the folder it's inserted into. The
   owner is whomever owns the Credentials used to make the Drive Service"
  [google-ctx parent-folder-id file-title file-description media-type]
  (let [file (doto (java.io.File/createTempFile "temp" "temp")
               assert)]
    (upload-file google-ctx file parent-folder-id file-title file-description media-type)))

(t/ann download-file [cred/GoogleCtx String String -> String])
(defn download-file
  "Given a google-ctx configuration map, a file id to download,
   and a media type, download the drive file and then read it in
   and return the result of reading the file"
  [google-ctx file-id media-type]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        files-get (doto (.get files file-id)
                    assert)
        file (cast File (doto (.execute files-get)
                          assert))
        http-request (doto (.getRequestFactory ^Drive drive-service)
                       assert)
        export-link (doto (.getExportLinks ^File file)
                      assert)
        generic-url (GenericUrl. ^String (doto (cast String (get export-link media-type))
                                                         assert))
        get-request (doto (.buildGetRequest http-request generic-url)
                      assert)
        response (doto (.execute get-request)
                   assert)
        input-stream (doto (.getContent response)
                       assert)]
    (slurp input-stream)))

(t/ann delete-file [cred/GoogleCtx String -> File])
(defn delete-file
  "Given a google-ctx configuration map, and a file
   id to delete, moves that file to the trash"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        delete-request (doto (.trash files file-id)
                         assert)]
    (cast File (doto (.execute delete-request)
                 assert))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; File Edits ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/ann update-file-title [cred/GoogleCtx String String -> File])
(defn update-file-title
  "Given a google-ctx configuration map, a file id, and a title,
   updates the title of the given file to the given title."
  [google-ctx file-id title]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        files-get (doto (.get files file-id)
                    assert)
        file (cast File (doto (.execute files-get)
                          assert))
        file (doto (.setTitle ^File file title)
               assert)
        update-request (doto (.update files file-id file)
                         assert)]
    (cast File (doto (.execute update-request)
                 assert))))

(t/ann update-file-description [cred/GoogleCtx String String -> File])
(defn update-file-description
  "Given a google-ctx configuration map, a file id, and a description,
   updates the description of the given file to the given description."
  [google-ctx file-id description]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        files-get (doto (.get files file-id)
                    assert)
        file (cast File (doto (.execute files-get)
                          assert))
        file (doto (.setDescription ^File file description)
               assert)
        update-request (doto (.update files file-id file)
                         assert)]
    (cast File (doto (.execute update-request)
                 assert))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;; File Properties Management ;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/ann get-properties [cred/GoogleCtx String -> (t/Seq Property)])
(defn get-properties
  "Given a google-ctx configuration map, and a file id, returns a
   list of all Properties associated with this file"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        properties (doto (.properties ^Drive drive-service)
                     assert)
        all-properties (doto (.list properties file-id)
                         assert)
        properties (cast PropertyList (doto (.execute all-properties)
                                        assert))]
    (tu/ignore-with-unchecked-cast
     (.getItems ^PropertyList properties)
     (t/Seq Property))))

(t/ann update-property [cred/GoogleCtx String String String String -> Property])
(defn update-property
  "Given a google-ctx configuration map, a file id, a key, a value, and
   a visibility(public or private) updates the property on this file to
   the new value if a property with the given key already exists, otherwise
   create a new one with this key value pair"
  [google-ctx file-id key value visibility]
  (let [drive-service (build-drive-service google-ctx)
        properties (doto (.properties ^Drive drive-service)
                     assert)
        property (doto (Property.)
                   (.setKey key)
                   (.setValue value)
                   (.setVisibility visibility))
        update-request (doto (.update properties file-id key property)
                         assert
                         (.setVisibility visibility))]
    (cast Property (doto (.execute update-request)
                     assert))))

(t/ann delete-property [cred/GoogleCtx String String String -> t/Any])
(defn delete-property
  "Given a google-ctx configuration map, a file id, and a key,
   deletes the property on this file associated with this key"
  [google-ctx file-id key visibility]
  (let [drive-service (build-drive-service google-ctx)
        properties (doto (.properties ^Drive drive-service)
                     assert)
        delete-request (doto (.delete properties file-id key)
                         assert
                         (.setVisibility visibility))]
    (.execute delete-request)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;; File Permissions Management ;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/ann get-permissions [cred/GoogleCtx String -> (t/Seq Permission)])
(defn get-permissions
  "Given a google-ctx configuration map, and a file-id, gets all of the
   permissions for the given file"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        permissions (doto (.permissions ^Drive drive-service)
                     assert)
        all-permissions (doto (.list permissions file-id)
                          assert)
        permissions (cast PermissionList (doto (.execute all-permissions)
                                           assert))]
    (tu/ignore-with-unchecked-cast
     (.getItems ^PermissionList permissions)
     (t/Seq Permission))))

(t/ann update-permission [cred/GoogleCtx String String String -> Permission])
(defn update-permission
  "Given a google-ctx configuration map, a file-id, an email address of the
   user who's permissions we are editing, and a new role for the user on this
   file(reader or writer, owner is not currently supported), adds or edits the
   permissions for this user on the given file"
  [google-ctx file-id email new-role]
  (let [drive-service (build-drive-service google-ctx)
        permissions (doto (.permissions ^Drive drive-service)
                      assert)
        permissions-for-file (tu/ignore-with-unchecked-cast
                              (set (map #(get % "emailAddress")
                                        (get-permissions google-ctx file-id)))
                              (t/Set String))
        id-request (doto (.getIdForEmail permissions email)
                        assert)
        permission-id (cast PermissionId (doto (.execute id-request)
                                              assert))
        permission-id (doto (.getId ^PermissionId permission-id)
                        assert)
        permission (doto (Permission.)
                     (.setEmailAddress email)
                     (.setRole new-role)
                     (.setId permission-id)
                     (.setType "user"))
        request (if (contains? permissions-for-file email)
                  (doto (.update permissions file-id permission-id permission)
                    assert)
                  (doto (.insert permissions file-id permission)
                    assert))]
    (tu/ignore-with-unchecked-cast (.execute request)
                                   Permission)))

(t/ann remove-permission [cred/GoogleCtx String String -> t/Any])
(defn remove-permission
  "Given a google-ctx configuration map, a file-id, and  an email address
   of the user who's permissions we are editing, removes this user from
   the permissions of the given file"
  [google-ctx file-id email]
  (let [drive-service (build-drive-service google-ctx)
        permissions (doto (.permissions ^Drive drive-service)
                      assert)
        permissions-for-file (tu/ignore-with-unchecked-cast
                              (set (map #(get % "emailAddress")
                                        (get-permissions google-ctx file-id)))
                              (t/Set String))
        id-request (doto (.getIdForEmail permissions email)
                        assert)
        permission-id (cast PermissionId (doto (.execute id-request)
                                              assert))
        permission-id (doto (.getId ^PermissionId permission-id)
                        assert)
        delete-request (doto (.delete permissions file-id permission-id)
                         assert)]
    (if (contains? permissions-for-file email)
      (.execute delete-request))))
