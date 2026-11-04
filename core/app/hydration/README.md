# Mount hydration

A mount folder delete checks the live remote subtree against the rows observed before the check begins. An unknown child or a changed file refuses the whole folder delete with `not_empty`; the rows and cache remain available, and the next normal enumeration can reveal the remote changes. A failed listing also prevents deletion. An unchanged observed subtree can still be deleted recursively.

This applies to ordinary and permanent folder deletes and costs one listing per folder visited. It narrows the poll-interval race but does not make deletion atomic: the provider interface has no conditional subtree delete, so a remote write after its folder was listed can still race the final delete.
