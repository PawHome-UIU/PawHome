/**
 * The species a room can be built for, and how to name one.
 *
 * ITS OWN FILE so that <SuiteModal/> can export only a component: a module
 * exporting both a component and a constant loses React fast refresh, which
 * eslint's react-refresh/only-export-components says out loud. Same call
 * lib/usePaged makes for the pagination hook.
 *
 * THREE, NOT THE SIX ON pets.species. SuiteSpecies is deliberately shorter --
 * a room is built for dogs, for cats, or for whatever else fits -- and NULL
 * (the empty value here) means it takes anything, which is a different
 * statement from "we have not decided".
 */
export const SPECIES = [
  { value: "", label: "Any species" },
  { value: "DOG", label: "Dogs only" },
  { value: "CAT", label: "Cats only" },
  { value: "OTHER", label: "Other animals only" },
];

export const speciesLabel = (s) =>
  SPECIES.find((o) => o.value === (s ?? ""))?.label ?? s;
