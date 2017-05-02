;; imports
(do
  (refer 'clojure.set)
  (union)
  (intersection #{1} #{1 2})
  )
(do
  (refer 'clojure.set :as <warning>s3</warning> :exclude '[intersection])
  (union)
  (<warning>intersection</warning> #{1} #{1 2})
  (<warning>s3</warning>/union)
  )
(do
  (refer 'clojure.set :rename {union union_renamed})
  (union_renamed)
  (<warning>union</warning>)
  )

(do
  (require 'clojure.set :as <warning>s1</warning>)
  (<warning>union</warning>)
  (<warning>s1</warning>/union)
  (require '[clojure.set :as s1])
  (<warning>union</warning>)
  (s1/union)
  )
(do
  (require '(clojure zip [set :as s2]))
  (<warning>union</warning>)
  (<warning>s1</warning>/union)
  (s2/union)
  )
(do
  (require '(clojure zip [set :refer  :all]))
  (union)
  (intersection #{1} #{1 2})
  )

(do
  (use '[clojure.set :as s1])
  (union)
  (s1/union)
  )
(do
  (use '(clojure zip [set :as s2]))
  (union)
  (s2/union)
  )
(do
  (use '(clojure zip [set :refer [union] :only [union]]))
  (union)
  (<warning>intersection</warning> #{1} #{1 2})
  )
