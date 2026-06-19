from app.models.ticket import Ticket
from app.repositories.ticket_repository import TicketRepository


class TicketService:
    def __init__(self) -> None:
        self.repository = TicketRepository()

    def create_ticket(self, title: str, priority: str) -> Ticket:
        ticket = Ticket(id="ticket-1", title=title, priority=priority, status="open")
        self.repository.save(ticket)
        return ticket

    def transition_status(self, ticket_id: str, status: str) -> Ticket:
        ticket = self.repository.find_by_id(ticket_id)
        ticket.status = status
        self.repository.save(ticket)
        return ticket
